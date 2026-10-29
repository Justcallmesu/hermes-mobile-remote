package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.DelegateStatus
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentActivity
import io.github.nideta231.hermesremote.data.SubagentReducer
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.data.displayModelName
import io.github.nideta231.hermesremote.data.formatDuration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateTest {
    private fun tool(status: ToolStatus, result: String? = null, goals: List<String> = listOf("Read docs", "Run tests")) =
        ChatItem.Tool("t1", Delegate.TOOL, "", result = result, status = status, goals = goals)

    @Test fun goalsFromTasksOrSingleGoal() {
        assertEquals(listOf("A", "Task 2"), Delegate.goals(JSONObject("""{"tasks":[{"goal":"A"},{}]}""")))
        assertEquals(listOf("Solo"), Delegate.goals(JSONObject("""{"goal":"Solo"}""")))
    }

    @Test fun liveToolStartCarriesTheGoals() {
        val items = LiveReducer.apply(emptyList(), "tool.start",
            JSONObject("""{"tool_id":"c1","name":"delegate_task","args":{"tasks":[{"goal":"A"},{"goal":"B"}]}}"""), "1")
        assertEquals(listOf("A", "B"), (items.single() as ChatItem.Tool).goals)
    }

    @Test fun settledResultsMarkTimeoutsFailed() {
        val rows = Delegate.rowsFromCall(tool(ToolStatus.OK,
            """{"results":[{"status":"ok","summary":"Found 3","duration_seconds":12.0,"model":"x/sonnet"},{"status":"timeout"}]}"""))
        assertEquals(listOf(DelegateStatus.DONE, DelegateStatus.FAILED), rows.map { it.status })
        assertEquals("Found 3", rows[0].activity.single().text)
        assertEquals(12.0, rows[0].durationSec)
    }

    @Test fun backgroundDispatchIsParkedNotSpinning() {
        val rows = Delegate.rowsFromCall(tool(ToolStatus.OK, """{"status":"dispatched","goals":["Read docs","Run tests"]}"""))
        assertTrue(rows.all { it.status == DelegateStatus.DISPATCHED })
        assertTrue(Delegate.rowsFromCall(tool(ToolStatus.RUNNING)).all { it.status == DelegateStatus.RUNNING })
    }

    @Test fun liveChildrenJoinByGoalAndLeftoversGoToThePanel() {
        val live = listOf(Subagent("s2", "Run tests", toolCount = 3), Subagent("s1", "read  DOCS"), Subagent("x", "Other", depth = 1))
        val (rows, rest) = Delegate.merge(listOf(tool(ToolStatus.RUNNING)), live)
        assertEquals(listOf("s1", "s2"), rows.getValue("t1").map { it.live?.id })
        assertEquals(listOf("x"), rest.map { it.id })
    }

    @Test fun activityStreamLikeTheDesktop() {
        var l = SubagentReducer.apply(emptyList(), "subagent.tool",
            JSONObject("""{"subagent_id":"a","goal":"G","tool_name":"read_file","tool_preview":"README.md","child_session_id":"c9"}"""), 0)
        l = SubagentReducer.apply(l, "subagent.thinking", JSONObject("""{"subagent_id":"a","text":"  hmm\n ok "}"""), 0)
        l = SubagentReducer.apply(l, "subagent.complete", JSONObject("""{"subagent_id":"a","status":"timeout","duration_seconds":61.5}"""), 0)
        val s = l.single()
        assertEquals("c9", s.sessionId)
        assertEquals(listOf("Read File(\"README.md\")", "hmm ok", "Timed out after 61s"), s.activity.map { it.text })
        assertEquals(SubagentActivity.Kind.SUMMARY, s.activity.last().kind)
        assertTrue(s.activity.last().error)
    }

    @Test fun rosterSeedsOnlyAColdRow() {
        val l = SubagentReducer.fromRoster(emptyList(), JSONObject("""{"subagents":[{"subagent_id":"a","goal":"G","last_tool":"web_search"}]}"""))
        assertEquals("Web Search", l.single().activity.single().text)
    }

    @Test fun labels() {
        assertEquals("claude-sonnet-4-6", displayModelName("anthropic/claude-sonnet-4-6"))
        assertEquals("4.2s", formatDuration(4.2))
        assertEquals("3m 05s", formatDuration(185.0))
        assertNull(Delegate.rowsFromCall(tool(ToolStatus.RUNNING, goals = emptyList())).firstOrNull())
    }
}
