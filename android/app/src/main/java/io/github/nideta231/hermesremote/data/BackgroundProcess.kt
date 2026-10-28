package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/**
 * A process the chat started in the background (Hermes' `terminal` with `background=true`):
 * a dev server, a build, a watcher. Read from Hermes' `process.list` for the open chat.
 */
data class BackgroundProcess(
    val id: String,
    val command: String,
    /** running or exited (Hermes' words). */
    val status: String = "running",
    val cwd: String? = null,
    val pid: Int? = null,
    val uptimeSec: Long? = null,
    val exitCode: Int? = null,
    /** The last few KB of its output. */
    val output: String = "",
) {
    val running: Boolean get() = status == "running"
    val failed: Boolean get() = !running && exitCode != null && exitCode != 0
}

object BackgroundProcesses {
    /** `process.list` result to rows: running ones first, then the newest that ended. */
    fun parse(result: JSONObject): List<BackgroundProcess> =
        result.optJSONArray("processes").objects().mapNotNull { r ->
            val id = r.str("session_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackgroundProcess(
                id = id,
                command = r.str("command").orEmpty(),
                status = r.str("status")?.takeIf { it.isNotBlank() } ?: "running",
                cwd = r.str("cwd")?.takeIf { it.isNotBlank() },
                pid = if (r.isNull("pid")) null else r.optInt("pid"),
                uptimeSec = if (r.isNull("uptime_seconds")) null else r.optLong("uptime_seconds"),
                exitCode = if (r.isNull("exit_code")) null else r.optInt("exit_code"),
                output = (r.str("output_tail") ?: r.str("output_preview")).orEmpty().trimEnd(),
            )
        }.sortedBy { if (it.running) 0 else 1 }
}
