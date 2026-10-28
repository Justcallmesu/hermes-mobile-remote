package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Which watched sessions still own background work (processes, subagents, loop/heartbeat), from polled results. */
object BackgroundWork {
    const val POLL_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 10_000L
    private val KINDS = listOf("process", "subagent", "control")

    /**
     * One poll of [runtimeIds] over [link]: returns [current] with each key turned on or off from the answer.
     * A call that fails or is refused leaves its key as it was; keys of sessions no longer watched are dropped.
     */
    suspend fun poll(link: ReattachLink, runtimeIds: Collection<String>, current: Set<String>): Set<String> {
        var out: Set<String> = current.filterTo(mutableSetOf()) { k -> runtimeIds.any { k.startsWith("$it:") } }
        for (rid in runtimeIds) {
            for (kind in KINDS) {
                try {
                    val params = JSONObject().put("session_id", rid)
                    when (kind) {
                        "process" -> link.call("process.list", params, CALL_TIMEOUT_MS).let { r ->
                            val rows = r.optJSONArray("processes")
                            out = withKey(out, "$rid:process", rows != null && (0 until rows.length()).any { rows.optJSONObject(it)?.optString("status") == "running" })
                        }
                        "subagent" -> out = withKey(out, "$rid:subagent",
                            link.call("subagent.list", params, CALL_TIMEOUT_MS).optJSONArray("subagents")?.length()?.let { it > 0 } ?: false)
                        else -> out = controlUpdate(rid, link.call("session.control.read", params, CALL_TIMEOUT_MS), out)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
        }
        return out
    }

    private fun withKey(keys: Set<String>, key: String, on: Boolean) = if (on) keys + key else keys - key

    /**
     * [current] after a payload carrying a `control` object ([session.control.read] answer or pushed `session.control.update`):
     * `<rid>:loop` and `<rid>:heartbeat` follow their entry (a null entry is off). Any other shape leaves [current] as it was.
     */
    fun controlUpdate(rid: String, payload: JSONObject, current: Set<String>): Set<String> {
        val control = payload.optJSONObject("control") ?: return current
        return listOf("loop", "heartbeat").filter { control.has(it) }
            .fold(current) { keys, k -> withKey(keys, "$rid:$k", control.optJSONObject(k)?.optString("status") == "active") }
    }

    /** The socket dropped: sessions that were working are resumed on the next Open and stop counting as busy, so the linger runs from now. */
    fun afterDrop(busy: Set<String>, toResume: Set<String>): Pair<Set<String>, Set<String>> = emptySet<String>() to (toResume + busy)
}
