package io.github.nideta231.hermesremote.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A newer build published on GitHub Releases. */
data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** SHA-256 GitHub computed for the uploaded asset, when it reports one. */
    val sha256: String?,
    val pageUrl: String,
)

/**
 * Self-update from GitHub Releases. This is the only traffic that leaves the bridge connection:
 * it talks to api.github.com and GitHub's download host over HTTPS, never sends the bridge token,
 * and installs only through Android's package installer, which refuses an APK that isn't signed
 * with the same key as the installed app.
 */
class Updater(private val context: Context, private val repo: String = REPO) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()

    val installedVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    // Last answer and its ETag: a repeat check sends If-None-Match, and GitHub's 304 for an unchanged
    // release does not count against the 60-requests-an-hour limit, so checking on every open is cheap.
    @Volatile private var etag: String? = null
    @Volatile private var lastRelease: AppUpdate? = null

    /** The latest release if it is newer than this build, else null. */
    suspend fun check(): AppUpdate? = runInterruptible(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 304 -> lastRelease
                r.code == 404 -> null  // no release published yet
                !r.isSuccessful -> throw IOException("GitHub answered ${r.code}")
                else -> parseRelease(JSONObject(r.body!!.string())).also {
                    lastRelease = it
                    etag = r.header("ETag")
                }
            }
        }?.takeIf { isNewer(it.version, installedVersion) }
    }

    /** Downloads the APK, checks its hash, and hands it to the system installer. */
    suspend fun install(update: AppUpdate, onProgress: (Float) -> Unit = {}) {
        val apk = download(update, onProgress)
        runInterruptible(Dispatchers.IO) { commit(apk) }
    }

    private suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = runInterruptible(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (update.sha256 != null && !update.sha256.equals(actual, ignoreCase = true)) {
            file.delete()
            throw IOException("Downloaded file is corrupted (checksum mismatch). Try again.")
        }
        file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once this app installed itself, Android 12+ may update it without another prompt.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    companion object {
        const val REPO = "nideta231/hermes-mobile-remote"

        fun parseRelease(o: JSONObject): AppUpdate? {
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val version = (o.str("tag_name") ?: return null).removePrefix("v")
            val assets = o.optJSONArray("assets").objects()
            val apk = assets.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            val sha = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return AppUpdate(version, o.str("body")?.trim().orEmpty(), apk.getString("browser_download_url"),
                apk.optLong("size", 0), sha, o.str("html_url") ?: "https://github.com/$REPO/releases")
        }

        /** Numeric comparison of dotted versions; "0.10.0" is newer than "0.9.3". Suffixes are ignored. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives the installer's verdict; asks the user to confirm when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the app restarts on the new version
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || reason.contains("signature", true))
                    "The update is signed with a different key than this install. Uninstall once, then install the release APK."
                else "Update failed: $reason"
                UpdateEvents.failure.value = hint
            }
        }
    }
}

/** Install failures reported from the receiver to whatever screen is showing. */
object UpdateEvents {
    val failure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
