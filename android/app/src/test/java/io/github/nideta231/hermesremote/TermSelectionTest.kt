package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.TermKeys
import io.github.nideta231.hermesremote.data.TermPoint
import io.github.nideta231.hermesremote.data.TermRun
import io.github.nideta231.hermesremote.data.TermScreen
import io.github.nideta231.hermesremote.data.TermSelection
import io.github.nideta231.hermesremote.data.selectedText
import io.github.nideta231.hermesremote.data.wordAt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermSelectionTest {
    private fun line(s: String) = listOf(TermRun(s, "default", "default", 0))
    private val rows = listOf(line("$ ls -la"), line("total 12   "), line("drwxr-xr-x  2 nath"))

    @Test fun copiesAcrossRowsWithoutTrailingBlanks() {
        val sel = TermSelection(TermPoint(2, 3), TermPoint(0, 2))  // dragged upwards
        assertEquals("ls -la\ntotal 12\ndrwx", selectedText(rows, sel))
    }

    @Test fun longPressPicksTheWord() {
        val w = wordAt(rows[2], 2, 14)
        assertEquals("nath", selectedText(rows, w))
        assertEquals("drwxr-xr-x", selectedText(rows, wordAt(rows[2], 2, 0)))
    }

    @Test fun selectedColumnsPerRow() {
        val sel = TermSelection(TermPoint(0, 2), TermPoint(1, 4))
        assertEquals(2..Int.MAX_VALUE - 1, sel.colsOn(0))
        assertEquals(0..4, sel.colsOn(1))
        assertEquals(null, sel.colsOn(2))
    }

    @Test fun pasteUsesBracketsOnlyWhenTheShellAsked() {
        assertEquals("\u001b[200~a\rb\u001b[201~", TermKeys.paste("a\nb", bracketed = true))
        assertEquals("a\rb", TermKeys.paste("a\r\nb", bracketed = false))
    }

    @Test fun screenTracksBracketedPasteAndJoinsScrollback() {
        var s = TermScreen(10, 2, List(2) { emptyList() })
        assertFalse(s.bracketedPaste)
        s = s.apply(JSONObject("""{"type":"screen","cols":10,"rows":2,"full":true,"paste":true,
            "lines":{"0":[["b","default","default",0]]},"scrolled":[[["a","default","default",0]]]}"""))
        assertTrue(s.bracketedPaste)
        assertEquals(3, s.all.size)
        assertEquals("a", s.all[0].first().text)
    }

    @Test fun ctrlKeysBeyondLetters() {
        assertEquals("\u001b", TermKeys.ctrl('['))
        assertEquals("\u0000", TermKeys.ctrl(' '))
        assertEquals("\u001f", TermKeys.ctrl('-'))
    }
}
