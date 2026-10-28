package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.SteerOutcome
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentOutput
import io.github.nideta231.hermesremote.data.SubagentReducer
import io.github.nideta231.hermesremote.data.SubagentTail
import io.github.nideta231.hermesremote.ui.tailText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentOutputTest {
    @Test fun readsATail() {
        val t = SubagentTail.parse(JSONObject("""{"subagent_id": "s", "available": true, "text": "line 1\nline 2\n", "truncated": true}"""))
        assertTrue(t.available)
        assertTrue(t.truncated)
        assertEquals("line 1\nline 2", tailText(t))
        val none = SubagentTail.parse(JSONObject("""{"subagent_id": "s", "available": false, "text": "", "truncated": false}"""))
        assertEquals("No output yet.", tailText(none))
        assertEquals("Loading…", tailText(null))
        assertEquals(SubagentTail(), SubagentTail.parse(JSONObject("{}")))
    }

    @Test fun queuedIsTheOnlySuccess() {
        assertEquals(SteerOutcome.QUEUED, SteerOutcome.parse(JSONObject("""{"status": "queued", "subagent_id": "s", "text": "x"}""")))
        assertEquals(SteerOutcome.REJECTED, SteerOutcome.parse(JSONObject("""{"status": "rejected"}""")))
        assertEquals(SteerOutcome.REJECTED, SteerOutcome.parse(JSONObject("{}")))
    }

    @Test fun steerIsOfferedUnlessHermesSaysNo() {
        assertTrue(Subagent("a", "A").steerable)  // events don't say; Hermes rejects if it must
        assertFalse(Subagent("a", "A", acceptingSteer = false).steerable)
        assertFalse(Subagent("a", "A", status = "completed").steerable)
        val known = listOf(Subagent("a", "A", acceptingSteer = true))
        val l = SubagentReducer.fromRoster(known, JSONObject("""{"subagents": [
            {"subagent_id": "a", "goal": "A", "status": "running", "accepting_steer": false},
            {"subagent_id": "b", "goal": "B", "status": "running"}]}"""))
        assertEquals(false, l[0].acceptingSteer)
        assertNull(l[1].acceptingSteer)
        assertTrue(l[1].steerable)
    }

    @Test fun steerSendsTrimmedTextAndReportsTheOutcome() = runBlocking {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val said = mutableListOf<String>()
        var done: Boolean? = null
        val out = SubagentOutput(CoroutineScope(Dispatchers.Unconfined),
            rpc = { method, params -> sent += method to params("rt"); JSONObject().put("status", "queued") },
            chatId = { "chat" }, live = { true }, describe = { it.message.orEmpty() }, say = { said += it })
        out.steer("s1", "  also echo ok  ") { done = it }
        assertEquals("subagent.steer", sent.single().first)
        val p = sent.single().second
        assertEquals(listOf("rt", "s1", "also echo ok"), listOf(p.getString("session_id"), p.getString("subagent_id"), p.getString("text")))
        assertEquals(true, done)
        assertEquals(SteerOutcome.QUEUED.message, said.single())
        out.steer("s1", "   ") { done = false }
        assertEquals(1, sent.size)  // blank text is never sent
    }

    @Test fun followStopsWhenAnotherChatOpens() = runBlocking {
        var chat = "c1"
        val tails = mutableListOf<SubagentTail>()
        val out = SubagentOutput(CoroutineScope(Dispatchers.Unconfined),
            rpc = { _, _ -> chat = "c2"; JSONObject("""{"available": true, "text": "hi", "truncated": false}""") },
            chatId = { chat }, live = { true }, describe = { "" }, say = {})
        out.follow("s1") { tails += it }  // returns instead of polling forever
        assertTrue(tails.isEmpty())  // the answer came for the old chat
    }
}
