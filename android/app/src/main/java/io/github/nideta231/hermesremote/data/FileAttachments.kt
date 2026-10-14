package io.github.nideta231.hermesremote.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Files (PDFs, documents, code, anything) sent with a message. Each is staged on the PC through
 * Hermes' `file.attach` and the message carries its `@file:` reference, as on the desktop.
 */
object FileAttachments {
    const val MAX_PER_MESSAGE = 6
    /** Base64 grows a file by a third and the bridge takes frames up to 16 MB. */
    const val MAX_BYTES = 10 * 1024 * 1024

    fun name(context: Context, uri: String): String = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: "file"

    suspend fun read(context: Context, uri: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val name = name(context, uri)
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BYTES) throw IOException("$name is over ${MAX_BYTES / (1024 * 1024)} MB")
            }
            buf.toByteArray()
        } ?: throw IOException("Couldn't read $name")
        bytes to name
    }

    fun dataUrl(bytes: ByteArray, mime: String?): String =
        "data:${mime ?: "application/octet-stream"};base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

/** The text sent to Hermes: what the user typed, then one `@file:` reference per line. */
fun withFileRefs(text: String, refs: List<String>): String =
    if (refs.isEmpty()) text else (listOf(text.trim()).filter { it.isNotEmpty() } + refs).joinToString("\n")
