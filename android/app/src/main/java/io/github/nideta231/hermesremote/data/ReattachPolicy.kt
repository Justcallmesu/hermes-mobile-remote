package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** What [ReattachPolicy] needs from the socket; [Gateway] is adapted to it by [LiveLink]. */
interface ReattachLink {
    val isOpen: Boolean
    fun forceReconnect()
    fun nudge()
    suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject
}

/**
 * After a socket drop the gateway reaps a detached session 20 s later (orphan reap grace) unless the
 * client resumes it. These are the decisions that get every watched session back inside that grace.
 */
object ReattachPolicy {
    const val PING_INTERVAL_MS = 10_000L
    const val PING_TIMEOUT_MS = 5_000L

    /** Delay before reconnect attempt [attempt] (0 = right after a drop): immediate, then 1 s, 2 s, 5 s, capped at 5 s. */
    fun retryDelayMs(attempt: Int): Long = when (attempt) { 0 -> 0L; 1 -> 1_000L; 2 -> 2_000L; else -> 5_000L }

    /** The socket is open again: `session.resume` for each watched session in [busy]. Returns old runtime id → new one. */
    suspend fun resumeWatched(link: ReattachLink, watched: Map<String, LiveLink.Watched>, busy: Set<String>): Map<String, String> {
        val moved = mutableMapOf<String, String>()
        for ((rid, w) in watched) {
            if (rid !in busy) continue
            try {
                val r = link.call("session.resume", sessionParams(w.profile).put("session_id", w.storedId), 60_000)
                r.optString("session_id").takeIf { it.isNotBlank() }?.let { moved[rid] = it }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
        return moved
    }

    /** Ping; one that gets no answer in 5 s drops the socket so the reconnect starts before the grace ends. Background only. */
    suspend fun heartbeat(link: ReattachLink, background: Boolean) {
        if (!background || !link.isOpen) return
        if (!alive(link)) link.forceReconnect()
    }

    /** Service start and network change: reconnect now if down; if it looks up, make sure it really is. */
    suspend fun reattach(link: ReattachLink) {
        if (link.isOpen) { if (!alive(link)) link.forceReconnect() } else link.nudge()
    }

    private suspend fun alive(link: ReattachLink): Boolean = try {
        link.call("ping", JSONObject(), PING_TIMEOUT_MS); true
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        false
    }
}

/** The watched sessions on disk, so a restarted process can resume them. A corrupt file reads as empty. */
class WatchedStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("watched_sessions", Context.MODE_PRIVATE))

    fun save(watched: Map<String, LiveLink.Watched>) {
        val arr = JSONArray()
        watched.forEach { (rid, w) ->
            arr.put(JSONObject().put("runtime", rid).put("stored", w.storedId).put("title", w.title ?: JSONObject.NULL).put("profile", w.profile ?: JSONObject.NULL))
        }
        prefs.edit().putString("watched", arr.toString()).apply()
    }

    fun load(): Map<String, LiveLink.Watched> = try {
        val arr = JSONArray(prefs.getString("watched", null) ?: "[]")
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.getString("runtime"), LiveLink.Watched(o.getString("stored"),
                    o.optString("title").takeIf { !o.isNull("title") && it.isNotEmpty() },
                    o.optString("profile").takeIf { !o.isNull("profile") && it.isNotEmpty() }))
            }
        }
    } catch (e: org.json.JSONException) {
        emptyMap()
    }
}
