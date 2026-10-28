package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeException
import io.github.nideta231.hermesremote.data.SearchHit
import io.github.nideta231.hermesremote.data.SessionSearch
import io.github.nideta231.hermesremote.data.SessionSearchSource
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.Target
import io.github.nideta231.hermesremote.data.parseSearchHits
import io.github.nideta231.hermesremote.data.serverOnlyHits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchTest {
    private fun summary(id: String) = SessionSummary(id, id, null, null, 0, null, null)

    @Test fun readsHitsWithTheirSnippet() {
        val hits = parseSearchHits(JSONObject("""{"sessions": [
            {"id": "s1", "title": "Router", "preview": "help", "message_count": 4, "last_active": 10.5,
             "pinned": true, "snippet": "set the firewall rule"},
            {"id": "s2", "title": null, "snippet": null},
            {"title": "no id, skipped"}
        ]}"""))
        assertEquals(listOf("s1", "s2"), hits.map { it.session.id })
        assertEquals("set the firewall rule", hits[0].snippet)
        assertTrue(hits[0].session.pinned)
        assertEquals(10.5, hits[0].session.lastActive!!, 0.0)
        assertEquals("", hits[1].snippet)
        assertEquals(emptyList<SearchHit>(), parseSearchHits(JSONObject("{}")))
    }

    @Test fun onlyChatsNotAlreadyShownAppearOnce() {
        val hits = listOf("a", "b", "c", "b").map { SearchHit(summary(it), "") }
        assertEquals(listOf("a", "c"), serverOnlyHits(hits, setOf("b")).map { it.session.id })
    }

    /** A fake bridge: records each query and answers when the test says. */
    private class FakeSource : SessionSearchSource {
        val asked = mutableListOf<Pair<String, String?>>()
        var reply = CompletableDeferred<List<SearchHit>>()
        override suspend fun searchSessions(query: String, limit: Int, profile: String?): List<SearchHit> {
            asked += query to profile
            return reply.await()
        }
    }

    /** Past the debounce. (advanceUntilIdle ignores backgroundScope, where the searches run.) */
    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    private fun TestScope.searcher(src: FakeSource, target: () -> Target) =
        SessionSearch(backgroundScope, source = { src }, target = target, debounceMs = 350)

    @Test fun waitsForAPauseAndAtLeastTwoCharacters() = runTest {
        val src = FakeSource()
        val s = searcher(src) { Target("pc", "default") }
        s.search("f")
        settle()
        assertTrue(src.asked.isEmpty())
        s.search("fi"); advanceTimeBy(100); s.search("fir"); advanceTimeBy(100); s.search("fire")
        advanceTimeBy(349); runCurrent()
        assertTrue(src.asked.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("fire" to "default"), src.asked)
        src.reply.complete(listOf(SearchHit(summary("s1"), "firewall")))
        runCurrent()
        assertEquals(listOf("s1"), s.state.value.hits.map { it.session.id })
        assertFalse(s.state.value.loading)
        assertEquals(Target("pc", "default"), s.state.value.target)
    }

    @Test fun dropsAReplyForAnOldQueryOrAnotherProfile() = runTest {
        val src = FakeSource()
        var target = Target("pc", "default")
        val s = searcher(src) { target }
        s.search("fire"); advanceTimeBy(400)
        val first = src.reply
        target = Target("pc", "work")  // switched profile while it was in flight
        first.complete(listOf(SearchHit(summary("s1"), "")))
        runCurrent()
        assertTrue(s.state.value.hits.isEmpty())
        // A new query cancels the old request; its reply never lands.
        src.reply = CompletableDeferred()
        s.search("water"); advanceTimeBy(400)
        assertEquals("water" to "work", src.asked.last())
    }

    @Test fun anOlderBridgeIsNotAskedAgain() = runTest {
        val src = object : SessionSearchSource {
            var calls = 0
            override suspend fun searchSessions(query: String, limit: Int, profile: String?): List<SearchHit> {
                calls++
                throw BridgeException(404, "not_found", "Not Found")
            }
        }
        val s = SessionSearch(backgroundScope, source = { src }, target = { Target("pc", "default") }, debounceMs = 350)
        s.search("fire"); settle()
        s.search("water"); settle()
        assertEquals(1, src.calls)
        assertTrue(s.state.value.hits.isEmpty() && !s.state.value.loading)
        s.clear()  // another PC may have a newer bridge
        s.search("water"); settle()
        assertEquals(2, src.calls)
    }
}
