package io.github.nideta231.hermesremote.data

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A Hermes cron job as the bridge shows it: what it is called, when it runs and how it went. What
 * it runs (prompt, script, delivery) stays on the PC.
 */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,
    /** scheduled, paused, completed or error (Hermes' own words). */
    val state: String,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastError: String?,
    val failureStreak: Int,
) {
    val paused: Boolean get() = state == "paused"
    val failing: Boolean get() = state == "error" || failureStreak > 0 || lastStatus == "error"
}

/** One past run of a job. [preview] is the start of its output. */
data class CronRun(val id: String, val title: String, val preview: String?, val startedAt: Long?)

fun parseCronJobs(o: JSONObject): List<CronJob> = o.optJSONArray("jobs").objects().mapNotNull(::parseCronJob)

fun parseCronJob(j: JSONObject): CronJob? {
    val id = j.str("id")?.takeIf { it.isNotBlank() } ?: return null
    return CronJob(id, j.str("name")?.takeIf { it.isNotBlank() } ?: id, j.str("schedule_display").orEmpty(),
        j.str("state") ?: "scheduled", instantMillis(j.opt("next_run_at")), instantMillis(j.opt("last_run_at")),
        j.str("last_status"), j.str("last_error")?.takeIf { it.isNotBlank() }, j.optInt("failure_streak"))
}

fun parseCronRuns(o: JSONObject): List<CronRun> = o.optJSONArray("runs").objects().mapNotNull { r ->
    val id = r.str("id") ?: return@mapNotNull null
    CronRun(id, r.str("title")?.takeIf { it.isNotBlank() } ?: "Run",
        r.str("preview")?.takeIf { it.isNotBlank() }, instantMillis(r.opt("started_at")))
}

/** Hermes writes times as ISO-8601 strings (jobs) or epoch seconds (runs); both become epoch millis. */
internal fun instantMillis(v: Any?): Long? = when (v) {
    is Number -> (v.toDouble() * 1000).toLong().takeIf { it > 0 }
    is String -> v.takeIf { it.isNotBlank() }?.let { s ->
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { (s.toDouble() * 1000).toLong() }.getOrNull()
    }
    else -> null
}
