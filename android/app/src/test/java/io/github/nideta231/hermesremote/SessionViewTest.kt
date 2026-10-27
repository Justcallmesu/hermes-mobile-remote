package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.DrawerCache
import io.github.nideta231.hermesremote.data.GroupOrder
import io.github.nideta231.hermesremote.data.moveGroup
import io.github.nideta231.hermesremote.data.SessionGrouping
import io.github.nideta231.hermesremote.data.SessionOrdering
import io.github.nideta231.hermesremote.data.SessionStatus
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.SessionView
import io.github.nideta231.hermesremote.data.arrangeSessions
import io.github.nideta231.hermesremote.data.dateGroupOf
import io.github.nideta231.hermesremote.data.statusOf
import io.github.nideta231.hermesremote.data.mergeSessionHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SessionViewTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 15, 0, 0) }
    private val nowSec = now.timeInMillis / 1000.0
    private val day = 86_400.0

    private fun s(id: String, ago: Double, pinned: Boolean = false, tokens: Long = 0, cost: Double = 0.0,
                  started: Double? = null, unread: Boolean = false) =
        SessionSummary(id, id, null, "desktop", 1, nowSec - ago, null, pinned, started, tokens, cost, unread)

    private val items = listOf(
        s("a", 60.0, tokens = 10, cost = 0.5),
        s("b", 3 * day, tokens = 900, cost = 0.1, started = nowSec - 40 * day),
        s("c", 40 * day, tokens = 50, cost = 2.0, unread = true),
        s("p", 100 * day, pinned = true),
    )
    private val projects = listOf(
        ProjectRef("proj1", "Website", setOf("b", "c")),
        ProjectRef(SessionView.NO_PROJECT, "Home", setOf("a", "p")),
    )

    private fun ids(groups: List<io.github.nideta231.hermesremote.data.SessionGroup>) =
        groups.map { g -> g.label to g.items.map { it.id } }

    @Test fun defaultGroupsByDateWithPinnedFirst() {
        val g = arrangeSessions(items, SessionView(), now = now)
        assertEquals(listOf("Pinned" to listOf("p"), "Today" to listOf("a"), "This week" to listOf("b"), "Older" to listOf("c")), ids(g))
    }

    @Test fun projectGroupsFollowLastUpdated() {
        val g = arrangeSessions(items, SessionView(grouping = SessionGrouping.PROJECT), projects = projects, now = now)
        // Home's newest chat (a, 1 min ago) beats Website's (b, 3 days ago): not alphabetical.
        assertEquals(listOf("Pinned" to listOf("p"), "Home" to listOf("a"), "Website" to listOf("b", "c")), ids(g))
        assertEquals("p-proj1", g[2].key)
    }

    @Test fun projectGroupsMoveWhenAChatGetsAReply() {
        val tree = listOf(ProjectRef("p1", "Website", setOf("w")), ProjectRef("p2", "Api", setOf("x")),
            ProjectRef("/r/alpha", "alpha", setOf("y"), auto = true))
        val view = SessionView(grouping = SessionGrouping.PROJECT)
        val before = arrangeSessions(listOf(s("w", 10.0), s("x", 20.0), s("y", 30.0)), view, projects = tree, now = now)
        assertEquals(listOf("Website", "Api", "alpha"), before.map { it.label })
        val after = arrangeSessions(listOf(s("w", 90.0), s("x", 50.0), s("y", 1.0)), view, projects = tree, now = now)
        assertEquals(listOf("alpha", "Api", "Website"), after.map { it.label })
    }

    @Test fun customGroupOrderWinsAndSurvivesReplies() {
        val tree = listOf(ProjectRef("p1", "Website", setOf("w")), ProjectRef("p2", "Api", setOf("x")),
            ProjectRef("p3", "Shop", setOf("y")))
        val view = SessionView(grouping = SessionGrouping.PROJECT, groupOrder = GroupOrder.CUSTOM)
        val custom = listOf("p-p3", "p-p1")
        val g = arrangeSessions(listOf(s("w", 10.0), s("x", 1.0), s("y", 30.0), s("h", 2.0)), view, projects = tree, now = now, customOrder = custom)
        // Known keys first in the saved order; new groups (Api, Home) after, in sort order.
        assertEquals(listOf("Shop", "Website", "Api", "Home"), g.map { it.label })
        // AUTO ignores the saved order.
        val auto = arrangeSessions(listOf(s("w", 10.0), s("x", 1.0), s("y", 30.0)), view.copy(groupOrder = GroupOrder.AUTO), projects = tree, now = now, customOrder = custom)
        assertEquals(listOf("Api", "Website", "Shop"), auto.map { it.label })
    }

    @Test fun movingGroupsKeepsHiddenKeys() {
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c", "zz"), moveGroup(shown, listOf("zz"), "a", 1))
        assertEquals(listOf("c", "a", "b"), moveGroup(shown, emptyList(), "c", Int.MIN_VALUE))
        assertEquals(listOf("b", "c", "a"), moveGroup(shown, emptyList(), "a", Int.MAX_VALUE))
        assertEquals(listOf("a", "b", "c"), moveGroup(shown, emptyList(), "a", -1))
        assertEquals(listOf("x"), moveGroup(shown, listOf("x"), "missing", 1))
    }

    @Test fun sessionsMissingFromTreeLandInHome() {
        val g = arrangeSessions(items + s("z", 10.0), SessionView(grouping = SessionGrouping.PROJECT), projects = projects, now = now)
        assertEquals(listOf("z", "a"), g.first { it.key == "p-" + SessionView.NO_PROJECT }.items.map { it.id })
    }

    @Test fun drawerCacheRoundTrips() {
        val sum = s("a", 60.0, tokens = 5, cost = 1.5, started = nowSec - 100, unread = true)
        assertEquals(sum, DrawerCache.sessionFrom(org.json.JSONObject(DrawerCache.sessionTo(sum).toString())))
        val pr = ProjectRef("p1", "Website", setOf("a", "b"), "/x", auto = true)
        assertEquals(pr, DrawerCache.projectFrom(org.json.JSONObject(DrawerCache.projectTo(pr).toString())))
    }

    @Test fun ordersByTokensAndCost() {
        val flat = { o: SessionOrdering -> arrangeSessions(items, SessionView(ordering = o), now = now).flatMap { it.items }.filterNot { it.pinned }.map { it.id } }
        // Within DATE grouping the order applies inside each bucket; check via status grouping (one bucket per status).
        val byTokens = arrangeSessions(items.filterNot { it.pinned }.map { it.copy(unread = false) },
            SessionView(grouping = SessionGrouping.STATUS, ordering = SessionOrdering.TOKENS), now = now)
        assertEquals(listOf("b", "c", "a"), byTokens.single().items.map { it.id })
        val byCost = arrangeSessions(items.filterNot { it.pinned }.map { it.copy(unread = false) },
            SessionView(grouping = SessionGrouping.STATUS, ordering = SessionOrdering.COST), now = now)
        assertEquals(listOf("c", "a", "b"), byCost.single().items.map { it.id })
        assertEquals(listOf("a", "b", "c"), flat(SessionOrdering.UPDATED))
    }

    @Test fun createdOrderingBucketsByStartTime() {
        val g = arrangeSessions(items, SessionView(ordering = SessionOrdering.CREATED), now = now)
        assertEquals(listOf("Pinned" to listOf("p"), "Today" to listOf("a"), "Older" to listOf("b", "c")), ids(g))
    }

    @Test fun statusUsesLiveStateThenUnread() {
        val live = mapOf("a" to "waiting", "b" to "running")
        assertEquals(SessionStatus.WAITING, statusOf(items[0], live))
        assertEquals(SessionStatus.WORKING, statusOf(items[1], live))
        assertEquals(SessionStatus.UNREAD, statusOf(items[2], live))
        assertEquals(SessionStatus.IDLE, statusOf(items[3], live))
        val g = arrangeSessions(items, SessionView(grouping = SessionGrouping.STATUS), live = live, now = now)
        assertEquals(listOf("Pinned", "Needs input", "Working", "Unread"), g.map { it.label })
    }

    @Test fun filtersByStatusProjectAndQuery() {
        val live = mapOf("a" to "running")
        val working = arrangeSessions(items, SessionView(statuses = setOf(SessionStatus.WORKING)), live = live, now = now)
        assertEquals(listOf("a"), working.flatMap { it.items }.map { it.id })
        val home = arrangeSessions(items, SessionView(projects = setOf(SessionView.NO_PROJECT)), projects = projects, now = now)
        assertEquals(setOf("a", "p"), home.flatMap { it.items }.map { it.id }.toSet())
        val q = arrangeSessions(items, SessionView(projects = setOf("proj1")), projects = projects, query = "c", now = now)
        assertEquals(listOf("c"), q.flatMap { it.items }.map { it.id })
    }

    @Test fun viewFlags() {
        assertFalse(SessionView().customized)
        assertFalse(SessionView(grouping = SessionGrouping.PROJECT).filtersActive)
        assertTrue(SessionView(grouping = SessionGrouping.PROJECT).customized)
        assertTrue(SessionView(statuses = setOf(SessionStatus.IDLE)).filtersActive)
    }

    @Test fun dateBuckets() {
        assertEquals("Today", dateGroupOf(nowSec - 60, now))
        assertEquals("Yesterday", dateGroupOf(nowSec - 1.2 * day, now))
        assertEquals("This month", dateGroupOf(nowSec - 10 * day, now))
        assertEquals("Older", dateGroupOf(null, now))
    }

    private fun row(id: String, t: Double) = SessionSummary(id, id, null, null, 1, t, null)

    @Test fun headMergeKeepsOlderPagesAndDropsDeleted() {
        val cur = listOf(row("a", 50.0), row("gone", 40.0), row("b", 30.0), row("old", 5.0))
        val merged = mergeSessionHead(cur, listOf(row("b", 60.0), row("a", 50.0), row("c", 20.0)))
        assertEquals(listOf("b", "a", "c", "old"), merged.map { it.id })
    }

    @Test fun equalTimestampsKeepAStableOrder() {
        val a = listOf(row("x", 10.0), row("y", 10.0), row("z", 10.0))
        val first = arrangeSessions(a, SessionView()).flatMap { g -> g.items.map { it.id } }
        val second = arrangeSessions(a.reversed(), SessionView()).flatMap { g -> g.items.map { it.id } }
        assertEquals(first, second)
    }
}
