package io.github.nideta231.hermesremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.nideta231.hermesremote.ui.insertNewline
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftEnterTest {
    @Test fun insertsAtCursor() {
        val r = insertNewline(TextFieldValue("helloworld", TextRange(5)))
        assertEquals("hello\nworld", r.text); assertEquals(TextRange(6), r.selection)
    }
    @Test fun replacesSelection() {
        val r = insertNewline(TextFieldValue("a XX b", TextRange(4, 2)))
        assertEquals("a \n b", r.text); assertEquals(TextRange(3), r.selection)
    }
    @Test fun emptyDraft() = assertEquals("\n", insertNewline(TextFieldValue("")).text)
}
