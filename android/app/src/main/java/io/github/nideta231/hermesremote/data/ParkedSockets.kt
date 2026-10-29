package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** One Hermes socket the bridge holds for a phone that dropped (`GET /v1/admin/parked`). [work] is empty when idle. */
data class ParkedSocket(val device: String, val heldSeconds: Int, val sessions: Int, val work: List<String>, val releasesIn: Int?)

/** What a held-work question is asked before. */
enum class HeldAction { Restart, Update }

/** The PC page's "Held chats": parsing the list, how a row reads, and whether Restart / Update must ask first. */
object ParkedSockets {
    fun parse(o: JSONObject): List<ParkedSocket> = o.optJSONArray("parked").objects().map {
        ParkedSocket(it.optString("device"), it.optInt("held_seconds"), it.optInt("sessions"),
            it.optJSONArray("work").strings(), if (it.isNull("releases_in")) null else it.optInt("releases_in"))
    }

    /** Why the socket is held; kinds this app does not know read as plain "working". */
    fun reason(p: ParkedSocket): String = when {
        p.work.isEmpty() -> p.releasesIn?.let { "idle, releases in ${(it + 59) / 60} min" } ?: "idle"
        else -> p.work.first().let { when (it) {
            "process" -> "background process"; "turn" -> "running turn"; "subagent" -> "subagent"
            "loop" -> "loop"; "heartbeat" -> "heartbeat"; else -> "working"
        } }
    }

    fun held(p: ParkedSocket): String = if (p.heldSeconds < 60) "${p.heldSeconds} s" else "${p.heldSeconds / 60} min"

    /** The question before a restart or update would end held work; null = go ahead (nothing working, or the bridge is too old to say). */
    fun confirmText(parked: List<ParkedSocket>, supported: Boolean, action: HeldAction = HeldAction.Restart): String? {
        val n = if (supported) parked.count { it.work.isNotEmpty() } else 0
        if (n == 0) return null
        val (verb, ing) = if (action == HeldAction.Update) "Update" to "updating" else "Restart" to "restarting"
        return if (n == 1) "1 chat on the PC is still working; $ing ends it. $verb anyway?"
        else "$n chats on the PC are still working; $ing ends them. $verb anyway?"
    }

    /** The "Held chats" section shows only for a bridge that lists them, and only when there is something to list. */
    fun showSection(parked: List<ParkedSocket>, supported: Boolean): Boolean = supported && parked.isNotEmpty()
}
