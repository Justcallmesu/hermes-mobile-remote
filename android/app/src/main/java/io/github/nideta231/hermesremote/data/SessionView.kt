package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * How the session drawer is grouped, sorted and filtered. Mirrors the desktop sidebar's
 * filter menu (grouping: date / project / status; ordering: updated / created / status /
 * tokens / cost; status and project filters).
 */
enum class SessionGrouping(val label: String) { DATE("Date"), PROJECT("Project"), STATUS("Status") }

enum class SessionOrdering(val label: String) {
    UPDATED("Last updated"), CREATED("Created"), STATUS("Status"), TOKENS("Tokens used"), COST("Cost")
}

/** A session's state right now. [order] is the "needs you first" order used by grouping and sorting. */
enum class SessionStatus(val label: String, val order: Int) {
    WAITING("Needs input", 0), WORKING("Working", 1), UNREAD("Unread", 2), IDLE("Idle", 3)
}

/**
 * A project from the backend's `projects.tree`; [sessionIds] is the membership desktop groups by.
 * [path] is its working root (null for Home): a chat created there belongs to the project.
 */
data class ProjectRef(val id: String, val label: String, val sessionIds: Set<String>, val path: String? = null)

data class SessionView(
    val grouping: SessionGrouping = SessionGrouping.DATE,
    val ordering: SessionOrdering = SessionOrdering.UPDATED,
    val statuses: Set<SessionStatus> = emptySet(),
    /** Project ids to show; [NO_PROJECT] is the backend's "Home" bucket for chats outside every project. */
    val projects: Set<String> = emptySet(),
) {
    val filtersActive: Boolean get() = statuses.isNotEmpty() || projects.isNotEmpty()
    val customized: Boolean get() = this != SessionView()

    companion object {
        const val NO_PROJECT = "__no_project__"
        const val NO_PROJECT_LABEL = "Home"
    }
}

/** [key] is unique per group (two projects may share a label). */
data class SessionGroup(val label: String, val items: List<SessionSummary>, val key: String = label)

fun statusOf(s: SessionSummary, live: Map<String, String>): SessionStatus = when (live[s.id]) {
    null, "idle" -> if (s.unread) SessionStatus.UNREAD else SessionStatus.IDLE
    "waiting" -> SessionStatus.WAITING
    else -> SessionStatus.WORKING
}

private val dateOrder = listOf("Today", "Yesterday", "This week", "This month", "Older")

fun dateGroupOf(ts: Double?, now: Calendar = Calendar.getInstance()): String {
    ts ?: return "Older"
    val then = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
    val days = ((now.timeInMillis - then.timeInMillis) / 86_400_000L).toInt()
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today"
        days < 2 -> "Yesterday"
        days < 7 -> "This week"
        days < 31 -> "This month"
        else -> "Older"
    }
}

/**
 * Filters, sorts and groups the drawer list. Pinned chats always lead in their own group,
 * like on desktop. Project groups follow the order of their most relevant session.
 */
fun arrangeSessions(
    items: List<SessionSummary>,
    view: SessionView,
    live: Map<String, String> = emptyMap(),
    projects: List<ProjectRef> = emptyList(),
    query: String = "",
    now: Calendar = Calendar.getInstance(),
): List<SessionGroup> {
    val projectOf = HashMap<String, ProjectRef>()
    projects.forEach { p -> p.sessionIds.forEach { projectOf.putIfAbsent(it, p) } }

    val visible = items.filter { s ->
        (query.isBlank() || s.displayTitle.contains(query, true) || (s.preview?.contains(query, true) ?: false)) &&
            (view.statuses.isEmpty() || statusOf(s, live) in view.statuses) &&
            (view.projects.isEmpty() || (projectOf[s.id]?.id ?: SessionView.NO_PROJECT) in view.projects)
    }
    val recency = compareByDescending<SessionSummary> { it.lastActive ?: 0.0 }
    val order: Comparator<SessionSummary> = when (view.ordering) {
        SessionOrdering.UPDATED -> recency
        SessionOrdering.CREATED -> compareByDescending<SessionSummary> { it.startedAt ?: it.lastActive ?: 0.0 }
        SessionOrdering.STATUS -> compareBy<SessionSummary> { statusOf(it, live).order }.then(recency)
        SessionOrdering.TOKENS -> compareByDescending<SessionSummary> { it.tokens }.then(recency)
        SessionOrdering.COST -> compareByDescending<SessionSummary> { it.costUsd }.then(recency)
    }
    // Id breaks ties, so equal timestamps never swap places between refreshes.
    val sorted = visible.sortedWith(order.thenBy { it.id })
    val (pinned, rest) = sorted.partition { it.pinned }

    val groups: List<SessionGroup> = when (view.grouping) {
        SessionGrouping.DATE -> {
            val key: (SessionSummary) -> Double? = if (view.ordering == SessionOrdering.CREATED) {
                { it.startedAt ?: it.lastActive }
            } else {
                { it.lastActive }
            }
            rest.groupBy { dateGroupOf(key(it), now) }.toSortedMap(compareBy { dateOrder.indexOf(it) })
                .map { (k, v) -> SessionGroup(k, v) }
        }
        SessionGrouping.STATUS -> rest.groupBy { statusOf(it, live) }.toSortedMap(compareBy { it.order })
            .map { (k, v) -> SessionGroup(k.label, v) }
        // Groups keep the first-appearance order of the already-sorted list; Home goes last.
        SessionGrouping.PROJECT -> rest.groupBy { projectOf[it.id]?.takeIf { p -> p.id != SessionView.NO_PROJECT } }
            .entries.sortedBy { it.key == null }
            .map { (k, v) -> SessionGroup(k?.label ?: SessionView.NO_PROJECT_LABEL, v, "p-" + (k?.id ?: SessionView.NO_PROJECT)) }
    }
    return if (pinned.isEmpty()) groups else listOf(SessionGroup("Pinned", pinned)) + groups
}

/** Chats a drawer group shows before "Show more" by default (Hermes desktop shows 3). Settings can change it. */
const val SESSION_FIRST_PAGE = 3

/** Choices offered in Settings for [SESSION_FIRST_PAGE]; 0 means show every chat. */
val SESSION_FIRST_PAGE_CHOICES = listOf(3, 5, 10, 0)

/** How many more chats each "Show more" tap adds. */
const val SESSION_PAGE = 10

/** A group as the drawer draws it: the first part of [group], and how many still wait behind "Show more". */
data class GroupPage(val group: SessionGroup, val items: List<SessionSummary>, val remaining: Int)

/**
 * Pages each group on its own, after [arrangeSessions] has searched, filtered and sorted the whole
 * list, so a search or filter always sees every chat and only the drawing is limited. [shown] holds
 * the groups the user expanded with "Show more" (group key -> rows). The open chat [currentId] is
 * never hidden behind "Show more".
 */
fun pageGroups(groups: List<SessionGroup>, shown: Map<String, Int>, currentId: String? = null, page: Int = SESSION_FIRST_PAGE): List<GroupPage> =
    groups.map { g ->
        val current = currentId?.let { id -> g.items.indexOfFirst { it.id == id } } ?: -1
        val first = if (page <= 0) g.items.size else page
        val n = maxOf(shown[g.key] ?: first, current + 1).coerceAtMost(g.items.size)
        GroupPage(g, g.items.take(n), g.items.size - n)
    }

/** Persists the drawer's [SessionView] (shared across paired PCs, like desktop's per-app prefs). */
class SessionViewStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("session_view", Context.MODE_PRIVATE))

    fun load(): SessionView = SessionView(
        grouping = enumOr(prefs.getString("grouping", null), SessionGrouping.DATE),
        ordering = enumOr(prefs.getString("ordering", null), SessionOrdering.UPDATED),
        statuses = prefs.getStringSet("statuses", emptySet()).orEmpty()
            .mapNotNull { n -> SessionStatus.entries.firstOrNull { it.name == n } }.toSet(),
        projects = prefs.getStringSet("projects", emptySet()).orEmpty().toSet(),
    )

    fun save(v: SessionView) {
        prefs.edit()
            .putString("grouping", v.grouping.name)
            .putString("ordering", v.ordering.name)
            .putStringSet("statuses", v.statuses.map { it.name }.toSet())
            .putStringSet("projects", v.projects.toSet())
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

/**
 * Merge a fresh newest-first [head] page into the chats already loaded. Chats in [head] replace
 * their old copies; an old chat that is missing from [head] but would sort inside it (newer than
 * its oldest row) was deleted and is dropped; everything older is kept as it was.
 */
fun mergeSessionHead(current: List<SessionSummary>, head: List<SessionSummary>): List<SessionSummary> {
    if (head.isEmpty()) return current
    val fresh = head.associateBy { it.id }
    val floor = head.minOf { it.lastActive ?: 0.0 }
    val kept = current.filter { it.id !in fresh && (it.lastActive ?: 0.0) < floor }
    return (head + kept).distinctBy { it.id }
}
