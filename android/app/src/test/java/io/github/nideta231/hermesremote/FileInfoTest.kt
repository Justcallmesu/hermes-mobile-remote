package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.humanSize
import io.github.nideta231.hermesremote.data.parseFileInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FileInfoTest {
    @Test fun sizesReadLikeAFileManager() {
        assertEquals("0 B", humanSize(0))
        assertEquals("1023 B", humanSize(1023))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("2.0 MB", humanSize(2L * 1024 * 1024))
        assertEquals("250 MB", humanSize(250L * 1024 * 1024))
        assertEquals("3.0 GB", humanSize(3L * 1024 * 1024 * 1024))
    }

    @Test fun parsesTheBridgeAnswer() {
        val i = parseFileInfo(JSONObject("""{"path":"/w/report.pdf","name":"report.pdf","exists":true,"size":9,"modified":1700000000,"mime":"application/pdf"}"""), "report.pdf")
        assertEquals("/w/report.pdf", i.path); assertEquals(9L, i.size); assertEquals("application/pdf", i.mime); assertEquals(1700000000L, i.modified)
        val gone = parseFileInfo(JSONObject("""{"path":"/w/gone.zip","name":"gone.zip","exists":false}"""), "gone.zip")
        assertFalse(gone.exists); assertNull(gone.size); assertNull(gone.mime)
    }
}
