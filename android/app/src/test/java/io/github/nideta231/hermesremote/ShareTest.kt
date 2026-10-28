package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Share
import io.github.nideta231.hermesremote.data.SharedContent
import io.github.nideta231.hermesremote.data.SharedStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {
    private val own = "io.github.nideta231.hermesremote.files"

    private fun parse(action: String? = Share.SEND, type: String? = "text/plain", subject: String? = null, text: String? = null,
                      streams: List<SharedStream> = emptyList()) = Share.parse(action, type, subject, text, streams, own)

    @Test fun onlySharesAreParsed() {
        assertNull(parse(action = "android.intent.action.VIEW", text = "hi"))
        assertNull(parse(action = null, text = "hi"))
    }

    @Test fun chromeLinkKeepsTitleAndUrlOnce() {
        assertEquals("Kotlin docs\nhttps://kotlinlang.org", parse(subject = "Kotlin docs", text = "https://kotlinlang.org")!!.text)
        assertEquals("Look https://x.y", parse(subject = "Look", text = "Look https://x.y")!!.text)
        assertEquals("only subject", parse(subject = " only subject ", text = null)!!.text)
        assertEquals("a link", parse(text = "https://x.y")!!.summary())
    }

    @Test fun picturesAndFilesAreSortedByType() {
        val s = parse(Share.SEND_MULTIPLE, "*/*", streams = listOf(
            SharedStream("content://media/1", "image/jpeg"),
            SharedStream("content://docs/a.pdf", "application/pdf"),
            SharedStream("content://docs/b", null)))!!
        assertEquals(listOf("content://media/1"), s.images)
        assertEquals(listOf("content://docs/a.pdf", "content://docs/b"), s.files)
        assertEquals(0, s.dropped)
        assertEquals("1 picture · 2 files", s.summary())
    }

    @Test fun unknownStreamTypeFallsBackToTheIntentType() {
        val s = parse(type = "image/*", streams = listOf(SharedStream("content://wa/1", null)))!!
        assertEquals(listOf("content://wa/1"), s.images)
    }

    @Test fun overTheLimitAndUnsafeStreamsAreDropped() {
        val many = (1..8).map { SharedStream("content://media/$it", "image/png") }
        val unsafe = listOf(SharedStream("file:///data/data/x/secret", "image/png"),
            SharedStream("content://$own/chat-files/1/a.txt", "text/plain"))
        val s = parse(Share.SEND_MULTIPLE, "image/*", streams = many + many.first() + unsafe)!!
        assertEquals(6, s.images.size)
        assertTrue(s.files.isEmpty())
        assertEquals(4, s.dropped)
    }

    @Test fun nothingUsableIsEmpty() {
        assertTrue(parse(text = "  ")!!.isEmpty)
        assertTrue(SharedContent().isEmpty)
    }

    @Test fun sharedTextGoesAboveTheDraft() {
        assertEquals("https://x.y\nmy note", Share.mergeDraft("my note", "https://x.y"))
        assertEquals("https://x.y", Share.mergeDraft("", "https://x.y"))
    }

    @Test fun onlyOurShareCopiesAreDeletable() {
        assertEquals("shared/123", Share.copyDir("content://$own/shared/123/photo.jpg", own))
        assertNull(Share.copyDir("content://$own/chat_files/1/a.txt", own))
        assertNull(Share.copyDir("content://$own/shared/../x/y", own))
        assertNull(Share.copyDir("content://media/shared/1/a", own))
    }
}
