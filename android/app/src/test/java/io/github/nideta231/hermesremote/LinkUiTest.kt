package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.LinkMenu
import io.github.nideta231.hermesremote.ui.linkCandidates
import io.github.nideta231.hermesremote.ui.linkMenu
import io.github.nideta231.hermesremote.ui.offerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkUiTest {
    private fun pc(id: String, gateway: Boolean, linked: Boolean = false) =
        PairedPc(id, id, "http://$id", active = false, gateway = gateway, linked = linked)

    @Test fun linkWithListsOnlyUnlinkedHostsOfTheOtherKind() {
        val b = pc("b", gateway = false)
        val pcs = listOf(b, pc("g1", true), pc("g2", true, linked = true), pc("b2", false))
        assertEquals(listOf("g1"), linkCandidates(pcs, b).map { it.id })
        assertFalse(linkMenu(listOf(b, pc("b2", false)), b).linkWith)
        assertTrue(linkMenu(pcs, b).linkWith)
    }

    @Test fun tagAndUnlinkShowOnlyForALinkedHost() {
        val g = pc("g", true, linked = true)
        val b = pc("b", false)
        assertEquals(LinkMenu(tag = true, unlink = true, linkWith = false), linkMenu(listOf(g, b), g))
        assertEquals(LinkMenu(tag = false, unlink = false, linkWith = true), linkMenu(listOf(g.copy(linked = false), b), b))
    }

    @Test fun offerSaysLinkForAPairAndPairForAFoundBridge() {
        val link = offerText(LinkOffer("g", "b", null, "Desk"))
        assertEquals("Link", link.confirm)
        assertEquals("Not now", link.dismiss)
        val pair = offerText(LinkOffer("g", null, "http://x", "Desk"))
        assertEquals("Pair", pair.confirm)
        assertEquals("Not now", pair.dismiss)
    }
}
