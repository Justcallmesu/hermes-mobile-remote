package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BackgroundWork
import io.github.nideta231.hermesremote.data.KeepAlive
import io.github.nideta231.hermesremote.data.KeepAlivePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveTest {
    private val linger = 5 * 60_000L

    @Test fun staysUpWhileBusyOrBackgroundWorkAndStopsOnlyAfterLinger() {
        assertTrue(KeepAlive.stayUp(setOf("a"), emptySet(), 10 * linger, true, linger))
        assertTrue(KeepAlive.stayUp(emptySet(), setOf("loop"), 10 * linger, true, linger))
        assertTrue(KeepAlive.stayUp(emptySet(), emptySet(), linger - 1, true, linger))
        assertFalse(KeepAlive.stayUp(emptySet(), emptySet(), linger, true, linger))
    }

    @Test fun sessionWorkingAtSocketDropKeepsTheServiceUpForTheLingerOnly() {
        val (busy, toResume) = BackgroundWork.afterDrop(setOf("r1"), emptySet())
        assertEquals(setOf("r1"), toResume)
        assertTrue(KeepAlive.stayUp(busy, emptySet(), linger - 1, true, linger))
        assertFalse(KeepAlive.stayUp(busy, emptySet(), linger, true, linger))
    }

    @Test fun plainFinishingTurnStopsAfterTheShortLingerWhenTheSettingIsOff() {
        assertTrue(KeepAlive.stayUp(emptySet(), emptySet(), 500, false, linger))
        assertFalse(KeepAlive.stayUp(emptySet(), emptySet(), 1_000, false, linger))
    }

    @Test fun restartWithoutAnIntentReopensTheSocketOnlyWhenItIsDown() {
        assertTrue(KeepAlive.needsReopen(restartedWithoutIntent = true, socketUp = false))
        assertFalse(KeepAlive.needsReopen(restartedWithoutIntent = true, socketUp = true))
        assertFalse(KeepAlive.needsReopen(restartedWithoutIntent = false, socketUp = false))
    }

    @Test fun timeoutRestartsOnceThenStops() {
        assertEquals(KeepAlive.OnTimeout.RESTART, KeepAlive.onTimeout(alreadyRestarted = false))
        assertEquals(KeepAlive.OnTimeout.STOP, KeepAlive.onTimeout(alreadyRestarted = true))
    }

    @Test fun settingsDefaultOnFiveMinutesAndPersist() {
        val mem = MemPrefs()
        val p = KeepAlivePrefs(mem)
        assertTrue(p.keepConnected)
        assertEquals(5, p.lingerMinutes)
        assertEquals(listOf(1, 5, 15), KeepAlivePrefs.LINGER_CHOICES)
        p.keepConnected = false
        p.lingerMinutes = 15
        val again = KeepAlivePrefs(mem)
        assertFalse(again.keepConnected)
        assertEquals(15, again.lingerMinutes)
        again.lingerMinutes = 7 // not a choice: ignored
        assertEquals(15, KeepAlivePrefs(mem).lingerMinutes)
    }
}
