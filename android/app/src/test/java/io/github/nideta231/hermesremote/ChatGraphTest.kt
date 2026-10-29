package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatGraphBuilder
import io.github.nideta231.hermesremote.data.GraphKind
import io.github.nideta231.hermesremote.data.GraphOptions
import io.github.nideta231.hermesremote.data.GraphSim
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.parseHermesSystemStats
import io.github.nideta231.hermesremote.ui.githubProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ChatGraphTest {
    private fun s(id: String, title: String, at: Double = 0.0, model: String? = null) =
        SessionSummary(id, title, null, null, 1, at, model)

    private val sessions = listOf(
        s("a", "Fix Odoo invoice report", 3.0, "claude-x"), s("b", "Odoo stock sync", 2.0, "claude-x"),
        s("c", "Android terminal selection", 1.0), s("d", "Untitled session"))
    private val projects = listOf(ProjectRef("p1", "Tritama", setOf("a", "b")), ProjectRef("__no_project__", "Home", setOf("c", "d")))

    @Test fun chatsLinkToTheirProjectAndSharedTopics() {
        val g = ChatGraphBuilder.build(sessions, projects)
        val labels = g.nodes.groupBy { it.kind }.mapValues { e -> e.value.map { it.label }.toSet() }
        assertEquals(setOf("Tritama"), labels[GraphKind.PROJECT])  // Home is not a project dot
        assertEquals(setOf("odoo"), labels[GraphKind.TOPIC])       // only words two chats share
        assertEquals(4, labels.getValue(GraphKind.CHAT).size)
        assertEquals(4, g.edges.size)                              // 2 project links + 2 topic links
        assertFalse(GraphKind.MODEL in labels)
        assertTrue(GraphKind.MODEL in ChatGraphBuilder.build(sessions, projects, GraphOptions(models = true)).nodes.map { it.kind })
    }

    @Test fun topicWordsDropStopWordsInBothLanguages() {
        assertEquals(setOf("odoo", "invoice", "report"), ChatGraphBuilder.topicWords("Fix the Odoo invoice report"))
        assertEquals(setOf("laporan", "odoo"), ChatGraphBuilder.topicWords("tolong cek laporan odoo dong"))
    }

    @Test fun layoutSpreadsDotsAndPullsLinkedOnesTogether() {
        val g = ChatGraphBuilder.build(sessions, projects)
        val sim = GraphSim(g)
        repeat(600) { sim.step() }
        assertTrue(sim.settled)
        val idx = g.nodes.withIndex().associate { it.value.id to it.index }
        fun d(a: String, b: String) = hypot(sim.x[idx.getValue(a)] - sim.x[idx.getValue(b)], sim.y[idx.getValue(a)] - sim.y[idx.getValue(b)])
        // A chat sits closer to its own project than to an unrelated chat.
        assertTrue(d("c:a", "p:p1") < d("c:a", "c:d"))
        assertTrue(sim.x.all { it.isFinite() } && sim.y.all { it.isFinite() })
    }

    @Test fun mentionsPointAtGithub() {
        assertEquals("https://github.com/LastNyx", githubProfile("@LastNyx"))
    }

    @Test fun gatewaySystemStats() {
        val now = parseHermesSystemStats(JSONObject("""{"hostname":"pc","cpu_count":16,"cpu_percent":3.7,
            "memory":{"total":17179869184,"used":8589934592,"percent":50.0},"disk":{"total":107374182400,"used":53687091200,"percent":50.0},
            "load_avg":[1.5,1,1],"uptime_seconds":120}"""))
        assertTrue(now.basic)
        assertEquals("pc", now.host)
        assertEquals(16.0, now.stats.memTotalGb, 0.01)
        assertEquals(50.0, now.stats.diskUsedGb!!, 0.01)
        assertEquals(1.5, now.stats.load1!!, 0.0)
    }
}
