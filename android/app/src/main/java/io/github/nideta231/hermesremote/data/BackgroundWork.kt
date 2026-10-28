package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Which watched sessions still own background work (processes, subagents, loop/heartbeat), from polled results. */
object BackgroundWork {
    const val POLL_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 10_000L
    private val KINDS = listOf("process", "subagent", "loop")

    /**
     * One poll of [runtimeIds] over [link]: returns [current] with each key turned on or off from the answer.
     * A call that fails or is refused leaves its key as it was; keys of sessions no longer watched are dropped.
     */
    suspend fun poll(link: ReattachLink, runtimeIds: Collection<String>, current: Set<String>): Set<String> {
        val out = current.filterTo(mutableSetOf()) { k -> runtimeIds.any { k.startsWith("$it:") } }
        for (rid in runtimeIds) {
            for (kind in KINDS) {
                val on = try {
                    val params = JSONObject().put("session_id", rid)
                    when (kind) {
                        "process" -> link.call("process.list", params, CALL_TIMEOUT_MS).let { r ->
                            val rows = r.optJSONArray("processes")
                            rows != null && (0 until rows.length()).any { rows.optJSONObject(it)?.optString("status") == "running" }
                        }
                        "subagent" -> link.call("subagent.list", params, CALL_TIMEOUT_MS).optJSONArray("subagents")?.length()?.let { it > 0 } ?: false
                        else -> controlActive(link.call("session.control.read", params, CALL_TIMEOUT_MS).optJSONObject("control"))
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    continue
                }
                if (on) out += "$rid:$kind" else out -= "$rid:$kind"
            }
        }
        return out
    }

    /** True when a loop or heartbeat in a `control` object is active. */
    fun controlActive(control: JSONObject?): Boolean =
        listOf("loop", "heartbeat").any { control?.optJSONObject(it)?.optString("status") == "active" }

    /** The socket dropped: sessions that were working are resumed on the next Open and stop counting as busy, so the linger runs from now. */
    fun afterDrop(busy: Set<String>, toResume: Set<String>): Pair<Set<String>, Set<String>> = emptySet<String>() to (toResume + busy)
}
