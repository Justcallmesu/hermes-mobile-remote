package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BpCard
import io.github.nideta231.hermesremote.data.BpComment
import io.github.nideta231.hermesremote.data.BpLatestComment
import io.github.nideta231.hermesremote.data.shouldNotify
import io.github.nideta231.hermesremote.data.unreadCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintSeenTest {
    private val card = BpCard("c1", "todo", "Login", comments = listOf(
        BpComment("m1", 100.0, "hermes", "comment", "started"),
        BpComment("m2", 200.0, "phone", "comment", "ok"),
        BpComment("m3", 300.0, "hermes", "question", "Stripe?"),
    ))

    @Test fun unreadCountsOnlyNewerHermesComments() {
        assertEquals(2, unreadCount(card, 0.0))
        assertEquals(1, unreadCount(card, 150.0))
        assertEquals(0, unreadCount(card, 300.0))
    }

    @Test fun notifiesOnceAndNeverOnTheFirstLook() {
        val m = BpLatestComment("c1", "Login", 300.0, "hermes", "question", "Stripe?")
        assertFalse(shouldNotify(m, null, 0.0))     // first look: baseline only
        assertTrue(shouldNotify(m, 200.0, 0.0))     // newer than the last alert
        assertFalse(shouldNotify(m, 300.0, 0.0))    // already alerted
        assertFalse(shouldNotify(m, 200.0, 300.0))  // already seen on the board
        assertFalse(shouldNotify(null, 200.0, 0.0))
    }
}
