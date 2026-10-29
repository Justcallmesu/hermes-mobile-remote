package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** One line of what a child did; the desktop's delegate card tickers the newest one. */
data class SubagentActivity(val kind: Kind, val text: String, val error: Boolean = false) {
    enum class Kind { TOOL, PROGRESS, THINKING, SUMMARY }
}

/**
 * A child agent the chat delegated work to (Hermes' `delegate_task`). Built from the `subagent.*`
 * events of the session and from `subagent.list` when the phone attaches mid-run.
 */
data class Subagent(
    val id: String,
    val goal: String,
    /** queued, running, completed, failed, error, timeout or interrupted (Hermes' words). */
    val status: String = "running",
    val model: String? = null,
    val toolCount: Int = 0,
    val lastTool: String? = null,
    /** 0 for a child of the chat's agent, 1 for a child of a child, ... */
    val depth: Int = 0,
    val startedAt: Long? = null,
    val durationSec: Double? = null,
    /** The child's final answer, cut short, once it ended. */
    val summary: String? = null,
    /** Hermes takes steering text for it; null when unknown (only `subagent.list` says). */
    val acceptingSteer: Boolean? = null,
    /** Latest relayed activity, oldest first (capped). */
    val activity: List<SubagentActivity> = emptyList(),
    /** The child's own stored session, when Hermes reported it: the row opens it. */
    val sessionId: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    /** Offer *Steer* unless Hermes said it won't take it. */
    val steerable: Boolean get() = running && acceptingSteer != false

    val running: Boolean get() = status == "running" || status == "queued"
    val failed: Boolean get() = status in FAILED

    private companion object {
        val FAILED = setOf("failed", "error", "timeout", "interrupted", "cancelled", "canceled")
    }
}

/** Folds the session's `subagent.*` events into the list of children. Pure. */
object SubagentReducer {
    /** True for the events this reducer reads. */
    fun handles(type: String) = type in EVENTS

    fun apply(list: List<Subagent>, type: String, p: JSONObject, nowMs: Long): List<Subagent> {
        if (type !in EVENTS) return list
        val id = p.str("subagent_id")?.takeIf { it.isNotBlank() } ?: return list  // older Hermes: nothing to key on
        val old = list.firstOrNull { it.id == id }
        val base = old ?: Subagent(id, goal = p.str("goal").orEmpty(), status = if (type == "subagent.spawn_requested") "queued" else "running",
            startedAt = nowMs)
        val next = base.copy(
            goal = p.str("goal")?.takeIf { it.isNotBlank() } ?: base.goal,
            model = p.str("model")?.takeIf { it.isNotBlank() } ?: base.model,
            depth = p.int("depth") ?: base.depth,
            toolCount = p.int("tool_count") ?: base.toolCount,
            sessionId = p.str("child_session_id")?.takeIf { it.isNotBlank() } ?: base.sessionId,
            inputTokens = p.int("input_tokens") ?: base.inputTokens,
            outputTokens = p.int("output_tokens") ?: base.outputTokens,
        ).let { s ->
            when (type) {
                "subagent.start" -> s.copy(status = "running")
                "subagent.tool" -> s.copy(status = "running", lastTool = p.str("tool_name") ?: s.lastTool,
                    toolCount = if (p.has("tool_count")) s.toolCount else s.toolCount + 1)
                "subagent.progress", "subagent.thinking" -> if (s.status == "queued") s.copy(status = "running") else s
                "subagent.complete" -> s.copy(status = p.str("status")?.takeIf { it.isNotBlank() } ?: "completed",
                    durationSec = p.dbl("duration_seconds") ?: s.durationSec,
                    summary = p.str("summary")?.trim()?.takeIf { it.isNotEmpty() }?.take(SUMMARY_MAX) ?: timeoutSummary(p) ?: s.summary)
                else -> s
            }
        }.let { s -> s.copy(activity = stream(p, type, s).fold(s.activity, ::append)) }
        return if (old == null) list + next else list.map { if (it.id == id) next else it }
    }

    /** `subagent.list` on attach: the live roster. Children we knew that ended meanwhile keep their last state. */
    fun fromRoster(known: List<Subagent>, roster: JSONObject): List<Subagent> {
        val live = roster.optJSONArray("subagents").objects().mapNotNull { r ->
            val id = r.str("subagent_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val prev = known.firstOrNull { it.id == id }
            val lastTool = r.str("last_tool") ?: prev?.lastTool
            Subagent(id, r.str("goal") ?: prev?.goal.orEmpty(), r.str("status") ?: "running",
                r.str("model") ?: prev?.model, r.optInt("tool_count", prev?.toolCount ?: 0),
                lastTool, r.optInt("depth", prev?.depth ?: 0),
                r.dbl("started_at")?.let { (it * 1000).toLong() } ?: prev?.startedAt,
                acceptingSteer = if (r.has("accepting_steer") && !r.isNull("accepting_steer")) r.optBoolean("accepting_steer")
                    else prev?.acceptingSteer,
                // A roster names the last tool, not a live call: it only seeds a row with no activity yet.
                activity = prev?.activity?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(lastTool?.let { SubagentActivity(SubagentActivity.Kind.TOOL, toolLabel(it)) }),
                sessionId = r.str("child_session_id") ?: prev?.sessionId,
                inputTokens = prev?.inputTokens, outputTokens = prev?.outputTokens)
        }
        val liveIds = live.map { it.id }.toSet()
        return known.filter { it.id !in liveIds && !it.running } + live
    }

    /** The user sent a new message: the children that ended belong to the last exchange. Running
     *  ones stay: Hermes runs delegations in the background, past the end of the turn that started
     *  them, and starts a turn of its own to deliver their result. */
    fun newTurn(list: List<Subagent>): List<Subagent> = list.filter { it.running }

    /** "read_file" -> "Read File", like the desktop. */
    fun toolLabel(name: String): String =
        name.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { name }

    /** `Read File("README.md")`, or the bare label without a preview. */
    fun formatTool(name: String, preview: String?): String {
        val snippet = compact(preview.orEmpty(), TOOL_PREVIEW_MAX)
        return if (snippet.isEmpty()) toolLabel(name) else "${toolLabel(name)}(\"$snippet\")"
    }

    private fun stream(p: JSONObject, type: String, s: Subagent): List<SubagentActivity> {
        val out = mutableListOf<SubagentActivity>()
        p.optJSONArray("output_tail").objects().forEach { t ->
            val tool = t.str("tool")?.takeIf { it.isNotBlank() }
            val line = if (tool != null) formatTool(tool, t.str("preview")) else compact(t.str("preview").orEmpty())
            if (line.isNotEmpty()) out += SubagentActivity(if (tool != null) SubagentActivity.Kind.TOOL else SubagentActivity.Kind.PROGRESS,
                line, t.optBoolean("is_error"))
        }
        val text = compact(p.str("text") ?: p.str("tool_preview").orEmpty())
        val error = p.has("error") && !p.isNull("error") && p.opt("error") != false
        when (type) {
            "subagent.tool" -> p.str("tool_name")?.takeIf { it.isNotBlank() }?.let {
                out += SubagentActivity(SubagentActivity.Kind.TOOL, formatTool(it, p.str("tool_preview") ?: p.str("text")), error)
            }
            "subagent.progress" -> if (text.isNotEmpty()) out += SubagentActivity(SubagentActivity.Kind.PROGRESS, text, error)
            "subagent.thinking" -> if (text.isNotEmpty()) out += SubagentActivity(SubagentActivity.Kind.THINKING, text)
            "subagent.complete" -> compact(s.summary.orEmpty()).takeIf { it.isNotEmpty() }?.let {
                out += SubagentActivity(SubagentActivity.Kind.SUMMARY, it, s.failed)
            }
        }
        return out
    }

    private fun append(list: List<SubagentActivity>, e: SubagentActivity): List<SubagentActivity> =
        if (list.lastOrNull() == e) list else (list + e).takeLast(MAX_STREAM)

    /** Hermes sends no summary on a hard timeout: say why the row failed. */
    private fun timeoutSummary(p: JSONObject): String? =
        if (p.str("status") == "timeout") "Timed out after ${p.dbl("duration_seconds")?.toInt() ?: "?"}s" else null

    private fun compact(text: String, max: Int = PREVIEW_MAX): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length > max) line.take(max - 1) + "…" else line
    }

    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    private val EVENTS = setOf("subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking",
        "subagent.tool", "subagent.complete")
    private const val SUMMARY_MAX = 600
    private const val MAX_STREAM = 24
    private const val PREVIEW_MAX = 220
    private const val TOOL_PREVIEW_MAX = 96
}
