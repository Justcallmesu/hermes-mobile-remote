package io.github.nideta231.hermesremote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Pictures in a chat. Outgoing: a picked photo is scaled to at most [MAX_SIDE] px and sent as JPEG
 * (a 12 MP camera shot is 4-8 MB; the model sees no more detail than this anyway, and the upload
 * stays quick over Tailscale). Incoming: images are fetched once through the bridge and cached.
 */
object ImageAttachments {
    const val MAX_PER_MESSAGE = 6
    private const val MAX_SIDE = 2048
    private const val QUALITY = 85

    /** Downloaded or read image bytes by reference, about 24 MB of them. */
    private val cache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** The picture at [uri], ready to upload: (bytes, file name). GIFs go as they are, so they keep moving. */
    suspend fun read(context: Context, uri: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "photo.jpg"
        val raw = resolver.openInputStream(u)?.use { it.readBytes() } ?: throw IOException("Couldn't read that picture")
        if (resolver.getType(u) == "image/gif" || name.endsWith(".gif", true)) return@withContext raw to name
        val out = shrink(raw) ?: return@withContext raw to name
        cache.put(uri, out)
        out to (name.substringBeforeLast('.') + ".jpg")
    }

    /** Bytes for [ref]: a phone `content://` uri, or a PC path that [fetch] gets over the bridge. */
    suspend fun load(context: Context, ref: String, fetch: suspend (String) -> ByteArray?): ByteArray? {
        cache.get(ref)?.let { return it }
        val bytes = runCatching {
            if (ref.startsWith("content://") || ref.startsWith("file://")) withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
            } else fetch(ref)
        }.getOrNull() ?: return null
        cache.put(ref, bytes)
        return bytes
    }

    /** A JPEG of [raw] no larger than [MAX_SIDE] on its long side, upright; null if it can't be decoded. */
    private fun shrink(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(bmp.width, bmp.height)
        if (long > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / long
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        val degrees = runCatching {
            when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it); it.toByteArray() }
    }
}
