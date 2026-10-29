package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/**
 * One child of a `delegate_task` call as the desktop's delegate card shows it: what the call
 * dispatched (its goals), what the live subagent events say it is doing, and how the result says
 * it ended, joined into one row.
 */
data class DelegateRow(
    val key: String,
    val goal: String,
    val status: DelegateStatus,
    val model: String? = null,
    val durationSec: Double? = null,
    val activity: List<SubagentActivity> = emptyList(),
    /** The live child, when one matched: its id steers/stops it, its session opens it. */
    val live: Subagent? = null,
) {
    val running get() = status == DelegateStatus.RUNNING || status == DelegateStatus.QUEUED
}

/** DISPATCHED: a background delegation nothing on this screen is watching (it must not spin). */
enum class DelegateStatus { QUEUED, RUNNING, DONE, FAILED, DISPATCHED }

object Delegate {
    const val TOOL = "delegate_task"

    /** The goals a `delegate_task` call dispatched, in task order. */
    fun goals(args: JSONObject?): List<String> {
        args ?: return emptyList()
        val tasks = args.optJSONArray("tasks")
        if (tasks != null && tasks.length() > 0) {
            return (0 until tasks.length()).map { i -> tasks.optJSONObject(i)?.str("goal")?.takeIf { it.isNotBlank() } ?: "Task ${i + 1}" }
        }
        return listOfNotNull(args.str("goal")?.takeIf { it.isNotBlank() })
    }

    /** Rows the call describes on its own: goals, plus how each ended once the result is in. */
    fun rowsFromCall(tool: ChatItem.Tool): List<DelegateRow> {
        val result = tool.result?.let { runCatching { JSONObject(it.trim()) }.getOrNull() }
        val finished = result?.optJSONArray("results").objects()
        val dispatched = if (result?.str("status") == "dispatched")
            result.optJSONArray("goals")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
        else emptyList()
        val titles = tool.goals.ifEmpty { dispatched.ifEmpty { finished.map { "Delegated task" } } }
        // Still being placed: running. Otherwise (a background dispatch, or a reloaded transcript that
        // carries no result) nothing here is watching it, so it is parked rather than spinning.
        val idle = when {
            tool.status == ToolStatus.RUNNING -> DelegateStatus.RUNNING
            tool.status == ToolStatus.FAILED && finished.isEmpty() -> DelegateStatus.FAILED
            else -> DelegateStatus.DISPATCHED
        }
        return titles.mapIndexed { i, goal ->
            val e = finished.getOrNull(i)
            val summary = e?.str("summary")?.trim()?.takeIf { it.isNotEmpty() } ?: e?.str("error")?.takeIf { it.isNotBlank() }
            DelegateRow("${tool.key}:$i", goal,
                status = if (e == null) idle else settled(e.str("status").orEmpty()),
                model = e?.str("model")?.takeIf { it.isNotBlank() },
                durationSec = e?.dbl("duration_seconds"),
                activity = listOfNotNull(summary?.let { SubagentActivity(SubagentActivity.Kind.SUMMARY, it.replace(Regex("\\s+"), " ").take(220)) }))
        }
    }

    /** Anything that is not a success renders as failed (a timed-out child must not show a green check). */
    private fun settled(status: String) =
        if (status.isEmpty() || status == "ok" || status == "completed" || status == "success") DelegateStatus.DONE else DelegateStatus.FAILED

    /**
     * Layer the live subagents over each delegate call's rows, claiming each child once: by goal
     * first, then by task order when the call and the unclaimed children agree on how many there
     * are. Returns the rows per call key and the children no call claimed (shown in the panel).
     */
    fun merge(tools: List<ChatItem.Tool>, live: List<Subagent>): Pair<Map<String, List<DelegateRow>>, List<Subagent>> {
        val unclaimed = live.filter { it.depth == 0 }.toMutableList()
        fun claim(pred: (Subagent) -> Boolean): Subagent? = unclaimed.indexOfFirst(pred).takeIf { it >= 0 }?.let { unclaimed.removeAt(it) }
        val out = LinkedHashMap<String, List<DelegateRow>>()
        // Newest call first: a goal repeated across calls belongs to the latest one.
        for (tool in tools.asReversed()) {
            val rows = rowsFromCall(tool)
            val byGoal = rows.map { r -> claim { norm(it.goal) == norm(r.goal) } }.toMutableList()
            val missing = byGoal.count { it == null }
            if (missing > 0 && missing == unclaimed.size && rows.isNotEmpty() && tool.status == ToolStatus.RUNNING) {
                for (i in rows.indices) if (byGoal[i] == null) byGoal[i] = claim { true }
            }
            out[tool.key] = rows.mapIndexed { i, r -> byGoal[i]?.let { withLive(r, it) } ?: r }
        }
        val nested = live.filter { it.depth > 0 }
        return out to (unclaimed + nested)
    }

    /** Live state wins where it exists: the result says it finished, only the events say what it is doing. */
    fun withLive(r: DelegateRow, s: Subagent) = r.copy(
        goal = s.goal.ifBlank { r.goal },
        status = when {
            s.status == "queued" -> DelegateStatus.QUEUED
            s.running -> DelegateStatus.RUNNING
            s.failed -> DelegateStatus.FAILED
            else -> DelegateStatus.DONE
        },
        model = s.model ?: r.model,
        durationSec = s.durationSec ?: r.durationSec,
        activity = s.activity.ifEmpty { r.activity },
        live = s)

    /** A panel row for a child no call on screen claimed. */
    fun fromSubagent(s: Subagent) = withLive(DelegateRow("sa:${s.id}", s.goal.ifBlank { "Subagent" }, DelegateStatus.RUNNING), s)

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()
}

/** "anthropic/claude-sonnet-4-6" -> "claude-sonnet-4-6". */
fun displayModelName(model: String): String = model.substringAfterLast('/').substringAfterLast(':').ifBlank { model }

/** Like the desktop: 850ms, 4.2s, 42s, 3m 05s, 1h 02m. */
fun formatDuration(sec: Double): String = when {
    sec < 0 || sec.isNaN() -> ""
    sec < 1 -> "${(sec * 1000).toInt().coerceAtLeast(1)}ms"
    sec < 10 -> "%.1fs".format(sec)
    sec < 60 -> "${sec.toInt()}s"
    sec < 3600 -> "%dm %02ds".format(sec.toInt() / 60, sec.toInt() % 60)
    else -> "%dh %02dm".format(sec.toInt() / 3600, sec.toInt() % 3600 / 60)
}
