package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The Android side of [Share]. Another app's `content://` grant lasts only as long as the share,
 * so each stream is copied into the cache right away and the composer holds a uri of our own
 * provider; [delete] drops a copy once it is sent or removed, [clearAll] at launch catches the rest.
 */
object SharedFiles {
    fun authority(context: Context) = context.packageName + ".files"

    /** The share [intent] carries, with its pictures and files still pointing at the sending app. */
    fun fromIntent(context: Context, intent: Intent): SharedContent? {
        val streams = streamUris(intent).map { SharedStream(it.toString(), context.contentResolver.getType(it)) }
        return Share.parse(intent.action, intent.type, intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), streams, authority(context))
    }

    /** [shared] with every stream copied into the cache; ones that can't be read, or are too big, count as dropped. */
    suspend fun copy(context: Context, shared: SharedContent): SharedContent = withContext(Dispatchers.IO) {
        val images = shared.images.mapNotNull { copyOne(context, it, limit = null) }
        val files = shared.files.mapNotNull { copyOne(context, it, limit = FileAttachments.MAX_BYTES) }
        shared.copy(images = images, files = files, dropped = shared.dropped + shared.uris.size - images.size - files.size)
    }

    /** Deletes the cache copies among [uris]; picker uris are left alone. */
    fun delete(context: Context, uris: Collection<String>) {
        uris.mapNotNull { Share.copyDir(it, authority(context)) }.forEach { File(context.cacheDir, it).deleteRecursively() }
    }

    fun clearAll(context: Context) {
        File(context.cacheDir, Share.CACHE_DIR).deleteRecursively()
    }

    private fun copyOne(context: Context, uri: String, limit: Int?): String? {
        val name = FileAttachments.name(context, uri).replace('/', '_').ifBlank { "file" }
        val dir = File(context.cacheDir, "${Share.CACHE_DIR}/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, name)
        val ok = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                out.outputStream().use { sink ->
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (limit != null && total > limit) throw IOException("$name is too big")
                        sink.write(chunk, 0, n)
                    }
                }
            } ?: throw IOException("Couldn't read $name")
        }.isSuccess
        if (!ok) { dir.deleteRecursively(); return null }
        return FileProvider.getUriForFile(context, authority(context), out).toString()
    }

    /** EXTRA_STREAM, single or a list; some apps only fill ClipData, so that counts too. */
    private fun streamUris(intent: Intent): List<Uri> {
        val extra: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
        }
        val clip = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        return (extra + clip).distinct()
    }
}
