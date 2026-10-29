package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ApprovalRequest
import io.github.nideta231.hermesremote.data.BackgroundProcess
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.ClarifyRequest
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.SecretRequest
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.ui.backgroundStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundStripTest {
    private fun proc(id: String, status: String = "running") = BackgroundProcess(id, "sleep 300", status)
    private fun sub(id: String, goal: String = "g$id") = Subagent(id, goal)
    private fun summary(state: ChatState, loop: Boolean = false, heartbeat: Boolean = false) =
        backgroundStrip(state, loop, heartbeat)?.summary

    @Test fun `no work hides the strip, each kind alone and combined shows its summary`() {
        assertNull(summary(ChatState()))
        assertNull(summary(ChatState(processes = listOf(proc("p", "exited")))))
        assertEquals("1 running", summary(ChatState(processes = listOf(proc("p")))))
        assertEquals("1 subagent", summary(ChatState(subagents = listOf(sub("a")))))
        assertEquals("loop", summary(ChatState(), loop = true))
        assertEquals("heartbeat", summary(ChatState(), heartbeat = true))
        assertEquals("2 running · 1 subagent · loop",
            summary(ChatState(processes = listOf(proc("p"), proc("q")), subagents = listOf(sub("a"))), loop = true))
        assertEquals("2 subagents · loop · heartbeat", summary(ChatState(subagents = listOf(sub("a"), sub("b"))), true, true))
    }

    @Test fun `an open approval, clarify or secret hides the strip`() {
        val work = ChatState(processes = listOf(proc("p")))
        val open = listOf(
            ChatItem.Approval("a", ApprovalRequest("i", null, "rm", "desc", listOf("once"))),
            ChatItem.Clarify("c", ClarifyRequest("i", null, emptyList())),
            ChatItem.Secret("s", SecretRequest("i", "secret", null, "t", null, "l")),
        )
        for (item in open) assertNull(summary(work.copy(items = listOf(item))))
    }

    @Test fun `subagents claimed by a delegate card are not counted`() {
        val tool = ChatItem.Tool("t1", Delegate.TOOL, "", status = ToolStatus.RUNNING, goals = listOf("fix bug"))
        val state = ChatState(items = listOf(tool), subagents = listOf(sub("a", "fix bug"), sub("b", "other")))
        assertEquals("1 subagent", summary(state))
    }
}
