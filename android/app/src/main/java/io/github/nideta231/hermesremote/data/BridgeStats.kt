package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** A timing histogram reduced to what a person reads: how many, the average, and the p95 bucket bound. */
data class Timing(val count: Long, val meanSec: Double, val p95Sec: Double?) {
    val empty: Boolean get() = count == 0L
}

/**
 * The bridge's own metrics (`GET /metrics?format=json`), summarised for the PC page: the same
 * figures as `hermes-remote-bridge metrics` on the PC.
 */
data class BridgeStats(
    val version: String = "",
    val startedAt: Long? = null,
    val pairedDevices: Int = 0,
    val connectedDevices: Int = 0,
    val liveSockets: Int = 0,
    val terminals: Int = 0,
    val requests: Long = 0,
    val failedRequests: Long = 0,
    val requestTiming: Timing = Timing(0, 0.0, null),
    val connectionsSinceStart: Long = 0,
    val framesToHermes: Long = 0,
    val framesFromHermes: Long = 0,
    val framesRefused: Long = 0,
    val rpcTiming: Timing = Timing(0, 0.0, null),
    val rpcErrors: Long = 0,
    /** Most-forwarded phone methods, busiest first. */
    val topMethods: List<Pair<String, Long>> = emptyList(),
    val questionsAsked: Long = 0,
    val questionsOpen: Int = 0,
    val questionWait: Timing = Timing(0, 0.0, null),
    val turnsStarted: Long = 0,
    /** Finished turns by status (complete, interrupted, error). */
    val turnsByStatus: Map<String, Long> = emptyMap(),
) {
    val turnsFinished: Long get() = turnsByStatus.values.sum()
}

/** Reads the JSON form of the bridge's metrics; unknown or missing metrics count as zero. */
fun parseBridgeStats(o: JSONObject): BridgeStats {
    val m = MetricsJson(o)
    val sockets = m.by("bridge_ws_connections", "endpoint")
    return BridgeStats(
        version = m.samples("bridge_info").firstOrNull()?.optJSONObject("labels")?.str("version").orEmpty(),
        startedAt = m.total("process_start_time_seconds").takeIf { it > 0 }?.let { (it * 1000).toLong() },
        pairedDevices = m.total("bridge_paired_devices").toInt(),
        connectedDevices = m.total("bridge_live_devices").toInt(),
        liveSockets = (sockets["live"] ?: 0.0).toInt(),
        terminals = (sockets["terminal"] ?: 0.0).toInt(),
        requests = m.total("bridge_http_requests_total").toLong(),
        failedRequests = m.samples("bridge_http_requests_total")
            .filter { (it.optJSONObject("labels")?.optString("status")?.toIntOrNull() ?: 0) >= 400 }
            .sumOf { it.optDouble("value", 0.0) }.toLong(),
        requestTiming = m.timing("bridge_http_request_seconds"),
        connectionsSinceStart = m.total("bridge_ws_connections_total", "endpoint" to "live").toLong(),
        framesToHermes = m.total("bridge_relay_frames_forwarded_total").toLong(),
        framesFromHermes = m.total("bridge_relay_frames_from_hermes_total").toLong(),
        framesRefused = m.total("bridge_relay_frames_refused_total").toLong(),
        rpcTiming = m.timing("bridge_hermes_rpc_seconds"),
        rpcErrors = m.total("bridge_hermes_rpc_errors_total").toLong(),
        topMethods = m.by("bridge_relay_frames_forwarded_total", "method").filterKeys { it != "response" }
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value.toLong() },
        questionsAsked = m.total("bridge_questions_total").toLong(),
        questionsOpen = m.total("bridge_questions_open").toInt(),
        questionWait = m.timing("bridge_question_wait_seconds"),
        turnsStarted = m.total("bridge_turns_started_total").toLong(),
        turnsByStatus = m.by("bridge_turns_completed_total", "status").mapValues { it.value.toLong() },
    )
}

/** Lookups over `{name: {type, help, samples: [{labels, value | count, sum, buckets}]}}`. */
private class MetricsJson(private val o: JSONObject) {
    fun samples(name: String): List<JSONObject> = o.optJSONObject(name)?.optJSONArray("samples").objects()

    fun total(name: String, vararg match: Pair<String, String>): Double = samples(name)
        .filter { s -> match.all { (k, v) -> s.optJSONObject("labels")?.optString(k) == v } }
        .sumOf { it.optDouble("value", 0.0) }

    fun by(name: String, label: String): Map<String, Double> = samples(name)
        .groupBy { it.optJSONObject("labels")?.optString(label).orEmpty() }
        .mapValues { (_, rows) -> rows.sumOf { it.optDouble("value", 0.0) } }

    /** Histograms merged across labels; p95 is the first bucket bound holding 95% of the observations. */
    fun timing(name: String): Timing {
        val rows = samples(name)
        val count = rows.sumOf { it.optLong("count") }
        if (count == 0L) return Timing(0, 0.0, null)
        val buckets = sortedMapOf<Double, Long>()
        rows.forEach { r ->
            val b = r.optJSONObject("buckets") ?: return@forEach
            b.keys().forEach { k -> k.toDoubleOrNull()?.let { bound -> buckets[bound] = (buckets[bound] ?: 0L) + b.optLong(k) } }
        }
        val p95 = buckets.entries.firstOrNull { it.value >= 0.95 * count }?.key
        return Timing(count, rows.sumOf { it.optDouble("sum", 0.0) } / count, p95)
    }
}

/** "42 ms", "3.1 s", "4 min": the same rounding as the CLI summary. */
fun formatSeconds(sec: Double): String = when {
    sec < 1 -> "%.0f ms".format(sec * 1000)
    sec < 120 -> "%.1f s".format(sec)
    else -> "%.0f min".format(sec / 60)
}
