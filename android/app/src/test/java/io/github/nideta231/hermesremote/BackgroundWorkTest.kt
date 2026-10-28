package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BackgroundWork
import io.github.nideta231.hermesremote.data.ReattachLink
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

private class PollLink(val answers: Map<String, JSONObject>, val failing: Set<String> = emptySet()) : ReattachLink {
    override val isOpen = true
    override fun forceReconnect() {}
    override fun nudge() {}
    override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        if (method in failing) throw IOException("-32601 method not found")
        return answers.getValue(method)
    }
}

private fun answers(running: Boolean, subagents: Boolean, loop: String?, heartbeat: String? = null) = mapOf(
    "process.list" to JSONObject().put("processes", JSONArray().put(JSONObject().put("status", if (running) "running" else "exited"))),
    "subagent.list" to JSONObject().put("subagents", JSONArray().apply { if (subagents) put(JSONObject().put("id", "a")) }),
    "session.control.read" to JSONObject().put("control", JSONObject()
        .put("loop", loop?.let { JSONObject().put("status", it) } ?: JSONObject.NULL)
        .put("heartbeat", heartbeat?.let { JSONObject().put("status", it) } ?: JSONObject.NULL)),
)

class BackgroundWorkTest {
    @Test fun runningProcessLiveSubagentOrActiveLoopAddsItsKeyAndNoneRemovesIt() = runBlocking {
        val on = BackgroundWork.poll(PollLink(answers(true, true, "active")), listOf("r1"), emptySet())
        assertEquals(setOf("r1:process", "r1:subagent", "r1:loop"), on)
        val hb = BackgroundWork.poll(PollLink(answers(false, false, "paused", "active")), listOf("r1"), emptySet())
        assertEquals(setOf("r1:loop"), hb)
        val off = BackgroundWork.poll(PollLink(answers(false, false, "done")), listOf("r1"), on)
        assertEquals(emptySet<String>(), off)
    }

    @Test fun refusedOrFailingCallLeavesTheSetUnchanged() = runBlocking {
        val current = setOf("r1:loop", "r1:process")
        val link = PollLink(answers(false, false, null), failing = setOf("session.control.read", "process.list"))
        assertEquals(current, BackgroundWork.poll(link, listOf("r1"), current))
    }
}
