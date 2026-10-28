package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.LiveLink
import io.github.nideta231.hermesremote.data.ReattachLink
import io.github.nideta231.hermesremote.data.ReattachPolicy
import io.github.nideta231.hermesremote.data.WatchedStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeLink(var open: Boolean = true, val pingFails: Boolean = false) : ReattachLink {
    override val isOpen get() = open
    val calls = mutableListOf<Pair<String, JSONObject>>()
    val timeouts = mutableListOf<Long>()
    var forced = 0
    var nudged = 0
    override fun forceReconnect() { forced++ }
    override fun nudge() { nudged++ }
    override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        calls += method to params
        timeouts += timeoutMs
        if (method == "ping" && pingFails) throw IOException("no answer")
        return JSONObject().put("session_id", "new-" + params.optString("session_id"))
    }
}

class ReattachPolicyTest {
    @Test fun dropReconnectsAtOnceThenCappedBackoff() {
        assertEquals(listOf(0L, 1_000L, 2_000L, 5_000L, 5_000L, 5_000L), (0..5).map { ReattachPolicy.retryDelayMs(it) })
    }

    @Test fun resumesOnlyRunningOrWaitingSessions() = runBlocking {
        val link = FakeLink()
        val watched = mapOf(
            "r1" to LiveLink.Watched("s1", "One", "work"),
            "r2" to LiveLink.Watched("s2", "Two"),
            "r3" to LiveLink.Watched("s3", "Three"),
        )
        val moved = ReattachPolicy.resumeWatched(link, watched, busy = setOf("r1", "r3"))
        val resumes = link.calls.filter { it.first == "session.resume" }.map { it.second.getString("session_id") }
        assertEquals(listOf("s1", "s3"), resumes.sorted())
        assertEquals("work", link.calls.first { it.second.optString("session_id") == "s1" }.second.getString("profile"))
        assertEquals(mapOf("r1" to "new-s1", "r3" to "new-s3"), moved)
    }

    @Test fun unansweredPingForcesReconnectOnlyInBackground() = runBlocking {
        val dead = FakeLink(pingFails = true)
        ReattachPolicy.heartbeat(dead, background = false)
        assertEquals(0, dead.calls.size)
        assertEquals(0, dead.forced)
        ReattachPolicy.heartbeat(dead, background = true)
        assertEquals(listOf(5_000L), dead.timeouts)
        assertEquals(1, dead.forced)
        val live = FakeLink()
        ReattachPolicy.heartbeat(live, background = true)
        assertEquals(0, live.forced)
        assertEquals(10_000L, ReattachPolicy.PING_INTERVAL_MS)
    }

    @Test fun reattachIsOneRoute() = runBlocking {
        val closed = FakeLink(open = false)
        ReattachPolicy.reattach(closed)
        assertEquals(1, closed.nudged)
        val half = FakeLink(pingFails = true)
        ReattachPolicy.reattach(half)
        assertEquals(1, half.forced)
        val fine = FakeLink()
        ReattachPolicy.reattach(fine)
        assertEquals(0, fine.forced + fine.nudged)
    }

    @Test fun watchedSessionsSaveLoadAndCorruptFileIgnored() {
        val prefs = MemPrefs()
        val store = WatchedStore(prefs)
        assertTrue(store.load().isEmpty())
        val data = mapOf("r1" to LiveLink.Watched("s1", "One", "work"), "r2" to LiveLink.Watched("s2", null))
        store.save(data)
        assertEquals(data, WatchedStore(prefs).load())
        prefs.map["watched"] = "{not json["
        assertTrue(WatchedStore(prefs).load().isEmpty())
    }
}
