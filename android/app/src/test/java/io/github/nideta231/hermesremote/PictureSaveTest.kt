package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.pictureExtension
import io.github.nideta231.hermesremote.ui.pictureFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureSaveTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)
    private val gif = "GIF89a".toByteArray()
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)

    @Test fun extensionFromMagicBytes() {
        assertEquals("png", pictureExtension(png))
        assertEquals("gif", pictureExtension(gif))
        assertEquals("webp", pictureExtension(webp))
        assertEquals("jpg", pictureExtension(jpg))
        assertEquals("jpg", pictureExtension(ByteArray(0)))
    }

    @Test fun keepsOriginalNameWithRealExtension() {
        assertEquals("chart.png", pictureFileName("/home/u/out/chart.jpg", png))
        assertEquals("my_shot", pictureFileName("@image:/tmp/my shot.png", jpg).substringBeforeLast('.'))
        assertEquals("photo.jpg", pictureFileName("content://media/external/photo.jpeg?x=1", jpg))
    }

    @Test fun fallsBackToTimestamp() {
        assertEquals("hermes-42.png", pictureFileName("content://media/external/images/12", png, now = 42))
    }
}
