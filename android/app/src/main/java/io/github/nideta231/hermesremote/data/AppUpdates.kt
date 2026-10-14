package io.github.nideta231.hermesremote.data

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateState(
    val installed: String = "",
    val available: AppUpdate? = null,
    val checking: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    val checkedAt: Long? = null,
    val error: String? = null,
    /** The version whose top-of-screen banner the user closed; the banner returns for a newer one. */
    val dismissed: String? = null,
    /** After an update: what changed since the version that ran before, shown once. */
    val whatsNew: String? = null,
) {
    /** The top-of-screen and drawer banner: an update the user hasn't closed (or one downloading). */
    val banner: Boolean get() = progress != null || (available != null && available.version != dismissed)
}

/**
 * Keeping the app up to date: checking GitHub releases, installing one, and showing what changed
 * after an update. Nothing here depends on the PC or the bridge.
 */
class AppUpdates(
    private val app: Application,
    private val scope: CoroutineScope,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val updater = Updater(app)
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _update = MutableStateFlow(UpdateState(installed = updater.installedVersion,
        dismissed = prefs.getString(KEY_DISMISSED_UPDATE, null)))
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** CHANGELOG.md as bundled in this APK (see build.gradle.kts); empty if missing. */
    val changelog: String by lazy {
        runCatching { app.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    private var checkJob: Job? = null
    private var lastUpdateCheck = 0L

    /**
     * Automatic update checks: every time the app opens or comes back to the foreground, then every
     * [CHECK_EVERY] while it stays on screen. Two opens within [CHECK_MIN_GAP] share
     * one check; repeats are conditional requests (see [Updater.check]), so they stay off the rate limit.
     */
    fun startChecks() {
        checkJob?.cancel()
        checkJob = scope.launch {
            // Counted from the last attempt, failed ones too, so being offline never means a retry loop.
            if (System.currentTimeMillis() - lastUpdateCheck >= CHECK_MIN_GAP) {
                lastUpdateCheck = System.currentTimeMillis()
                checkForUpdate(quiet = true)
            }
            while (true) {
                delay(CHECK_EVERY)
                lastUpdateCheck = System.currentTimeMillis()
                checkForUpdate(quiet = true)
            }
        }
    }

    /** Closes the top-of-screen banner for this version; Settings and the drawer still offer it. */
    fun dismissUpdateBanner() {
        val v = _update.value.available?.version ?: return
        prefs.edit().putString(KEY_DISMISSED_UPDATE, v).apply()
        _update.update { it.copy(dismissed = v) }
    }

    /**
     * First start after an update: show what changed since the version that ran before. A fresh
     * install shows nothing. Versions before 1.5.0 didn't record themselves, so an update from one of
     * them shows the new version's changes only.
     */
    fun showWhatsNew() {
        val installed = updater.installedVersion
        val last = prefs.getString(KEY_LAST_VERSION, null)
        if (last == installed) return
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val freshInstall = last == null && info != null && info.firstInstallTime == info.lastUpdateTime
        if (freshInstall || (last != null && !Updater.isNewer(installed, last))) { markWhatsNewSeen(); return }
        val notes = changesBetween(changelog, last, installed)
        if (notes.isBlank()) markWhatsNewSeen() else _update.update { it.copy(whatsNew = notes) }
    }

    fun markWhatsNewSeen() {
        prefs.edit().putString(KEY_LAST_VERSION, updater.installedVersion).apply()
        _update.update { it.copy(whatsNew = null) }
    }

    /** What the available update brings over this build: its notes cut to the versions in between. */
    private fun updateNotes(u: AppUpdate): String =
        changesBetween(u.notes, updater.installedVersion, u.version).ifBlank { u.notes }

    fun checkForUpdate(quiet: Boolean = false) {
        if (_update.value.checking || _update.value.progress != null) return
        _update.update { it.copy(checking = true, error = null) }
        scope.launch {
            try {
                val found = updater.check()
                // The release notes are the whole changelog; keep only what is newer than this build.
                _update.update { it.copy(available = found?.let { u -> u.copy(notes = updateNotes(u)) }, checking = false,
                    checkedAt = System.currentTimeMillis()) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _update.update { it.copy(checking = false, error = if (quiet) null else "Couldn't check for updates: ${t.message}") }
            }
        }
    }

    fun installUpdate() {
        val u = _update.value.available ?: return
        if (_update.value.progress != null) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            say("Allow installs from Hermes Remote, then tap Update again.")
            return
        }
        _update.update { it.copy(progress = 0f, error = null) }
        scope.launch {
            try {
                updater.install(u) { p -> _update.update { it.copy(progress = p) } }
                _update.update { it.copy(progress = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _update.update { it.copy(progress = null, error = t.message) }
                say(describe(t))
            }
        }
    }

    fun stopChecks() { checkJob?.cancel() }

    init {
        scope.launch {
            UpdateEvents.failure.collect { msg ->
                if (msg != null) {
                    _update.update { it.copy(progress = null, error = msg) }
                    say(msg)
                    UpdateEvents.failure.value = null
                }
            }
        }
    }

    private companion object {
        const val CHECK_EVERY = 60 * 60 * 1000L
        const val CHECK_MIN_GAP = 60 * 1000L
        const val KEY_LAST_VERSION = "last_version"
        const val KEY_DISMISSED_UPDATE = "dismissed_update"
    }
}
