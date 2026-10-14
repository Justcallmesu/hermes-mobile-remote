package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.MAX_DISPLAY_CHARS
import io.github.nideta231.hermesremote.ui.MAX_LINE_CHARS
import io.github.nideta231.hermesremote.ui.clipForDisplay
import io.github.nideta231.hermesremote.ui.displaySafe
import io.github.nideta231.hermesremote.ui.parseCached
import io.github.nideta231.hermesremote.ui.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old chats with huge messages or pictures must scroll without freezing or running out of memory. */
class ScrollSafetyTest {
    @Test fun shortTextIsUntouched() {
        assertEquals("hello\nworld", clipForDisplay("hello\nworld"))
        assertEquals("a\nb", displaySafe("a\nb"))
    }

    @Test fun hugeTextIsClippedWithANote() {
        val big = "line of output\n".repeat(10_000)
        val out = clipForDisplay(big)
        assertTrue(out.length < MAX_DISPLAY_CHARS + 100)
        assertTrue(out.endsWith("use Copy to get all of it)"))
    }

    @Test fun overlongLinesAreCutForUnwrappedText() {
        val out = displaySafe("x".repeat(5_000) + "\nshort")
        val lines = out.split('\n')
        assertTrue(lines[0].length < MAX_LINE_CHARS + 20)
        assertEquals("short", lines[1])
    }

    @Test fun picturesAreDownsampledToTheScreen() {
        assertEquals(1, sampleSizeFor(1080, 720, 1080))
        assertEquals(2, sampleSizeFor(3840, 2160, 1080))  // 4K screenshot -> 1920 px, not 3840
        assertEquals(4, sampleSizeFor(8000, 6000, 1080))
    }

    @Test fun parsedMarkdownIsReused() {
        val text = "# title\n```\ncode\n```"
        assertSame(parseCached(text), parseCached(text))
    }
}
