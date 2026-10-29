package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Note
import io.github.nideta231.hermesremote.data.NoteLine
import io.github.nideta231.hermesremote.data.NoteText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val body = "Plan\n- [ ] paint\n  - [x] buy brush\n- [ ] call Kevin\n"

    @Test fun checklistRoundTripsAndToggles() {
        val ls = NoteText.lines(body)
        assertEquals(NoteLine.Check("buy brush", true, "  "), ls[2])
        assertEquals(body, NoteText.join(ls))
        assertEquals("Plan\n- [x] paint\n  - [x] buy brush\n- [ ] call Kevin\n", NoteText.toggle(body, 1))
        assertEquals(1 to 3, NoteText.progress(body))
    }

    @Test fun convertBetweenTextAndChecklist() {
        val list = NoteText.toggleChecklist("milk\n- eggs\n\nbread")
        assertEquals("- [ ] milk\n- [ ] eggs\n- [ ] bread\n", list)
        assertTrue(NoteText.isChecklist(list))
        assertFalse(NoteText.isChecklist(NoteText.toggleChecklist(list)))
    }

    @Test fun parsesBridgeNote() {
        val n = Note.parse(JSONObject("""{"id":"x1","title":"T","body":"","color":"yellow","pinned":true,"labels":["plans",""]}"""))
        assertEquals(listOf("plans"), n.labels)
        assertTrue(n.pinned && !n.isEmpty)
    }
}
