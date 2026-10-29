package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.HeldAction
import io.github.nideta231.hermesremote.data.ParkedSocket
import io.github.nideta231.hermesremote.data.ParkedSockets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedSocketsTest {
    private val idle = ParkedSocket("Pixel", 120, 1, emptyList(), 480)
    private val busy = ParkedSocket("Tab", 300, 2, listOf("process"), null)

    @Test fun noWorkNoConfirm() {
        assertNull(ParkedSockets.confirmText(listOf(idle), supported = true))
        assertNull(ParkedSockets.confirmText(emptyList(), supported = true))
    }

    @Test fun workAsksWithCount() {
        assertEquals("1 chat on the PC is still working; restarting ends it. Restart anyway?",
            ParkedSockets.confirmText(listOf(idle, busy), supported = true))
        assertEquals("2 chats on the PC are still working; restarting ends them. Restart anyway?",
            ParkedSockets.confirmText(listOf(busy, busy.copy(device = "x", work = listOf("turn", "loop"))), supported = true))
    }

    @Test fun updateWording() {
        val u = HeldAction.Update
        assertEquals("1 chat on the PC is still working; updating ends it. Update anyway?",
            ParkedSockets.confirmText(listOf(idle, busy), supported = true, action = u))
        assertEquals("2 chats on the PC are still working; updating ends them. Update anyway?",
            ParkedSockets.confirmText(listOf(busy, busy.copy(device = "x")), supported = true, action = u))
        assertNull(ParkedSockets.confirmText(listOf(idle), supported = true, action = u))
        assertEquals("1 chat on the PC is still working; restarting ends it. Restart anyway?",
            ParkedSockets.confirmText(listOf(busy), supported = true, action = HeldAction.Restart))
    }

    @Test fun sectionVisibility() {
        assertEquals(false, ParkedSockets.showSection(emptyList(), supported = true))
        assertEquals(false, ParkedSockets.showSection(listOf(idle), supported = false))
        assertEquals(true, ParkedSockets.showSection(listOf(idle), supported = true))
    }

    @Test fun oldBridgeNoConfirm() {
        assertNull(ParkedSockets.confirmText(listOf(busy), supported = false))
    }

    @Test fun parsesListEmptyAndUnknownKinds() {
        val o = JSONObject("""{"parked":[{"device":"Pixel","held_seconds":90,"sessions":2,"work":["process","weird"],"releases_in":null},
            {"device":"Tab","held_seconds":10,"sessions":1,"work":[],"releases_in":480}]}""")
        val list = ParkedSockets.parse(o)
        assertEquals(listOf(ParkedSocket("Pixel", 90, 2, listOf("process", "weird"), null), ParkedSocket("Tab", 10, 1, emptyList(), 480)), list)
        assertEquals("background process", ParkedSockets.reason(list[0].copy(work = listOf("process"))))
        assertEquals("working", ParkedSockets.reason(list[0].copy(work = listOf("weird"))))
        assertEquals("idle, releases in 8 min", ParkedSockets.reason(list[1]))
        assertEquals(emptyList<ParkedSocket>(), ParkedSockets.parse(JSONObject("{}")))
        assertEquals(emptyList<ParkedSocket>(), ParkedSockets.parse(JSONObject("""{"parked":[]}""")))
    }
}
