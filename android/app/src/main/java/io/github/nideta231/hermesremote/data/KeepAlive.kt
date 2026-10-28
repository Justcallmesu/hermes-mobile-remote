package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences

/** Pure decisions behind [WatchService]: when the process and socket may go, and what a restart must do. */
object KeepAlive {
    /** Without "Keep connected in the background": the old wait for the "finished" notification to post. */
    const val SHORT_LINGER_MS = 1_000L

    /** [sinceLastBusyMs]: time since a turn or background work was last live. */
    fun stayUp(
        busy: Set<String>,
        backgroundWork: Set<String>,
        sinceLastBusyMs: Long,
        keepConnected: Boolean,
        lingerMs: Long,
    ): Boolean =
        busy.isNotEmpty() || backgroundWork.isNotEmpty() ||
            sinceLastBusyMs < (if (keepConnected) lingerMs else SHORT_LINGER_MS)

    /** A sticky restart arrives with no intent and a fresh process, so no socket: open it again. */
    fun needsReopen(restartedWithoutIntent: Boolean, socketUp: Boolean): Boolean = restartedWithoutIntent && !socketUp

    enum class OnTimeout { RESTART, STOP }

    /** Android 14+ ends a data-sync service after its time limit: start once more, then give up. */
    fun onTimeout(alreadyRestarted: Boolean): OnTimeout = if (alreadyRestarted) OnTimeout.STOP else OnTimeout.RESTART
}

/** The two background-keepalive settings, app-wide, read by the service as well as the settings screen. */
class KeepAlivePrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("keepalive", Context.MODE_PRIVATE))

    var keepConnected: Boolean
        get() = prefs.getBoolean("keep_connected", true)
        set(v) { prefs.edit().putBoolean("keep_connected", v).apply() }

    var lingerMinutes: Int
        get() = prefs.getInt("linger_minutes", 5).takeIf { it in LINGER_CHOICES } ?: 5
        set(v) { if (v in LINGER_CHOICES) prefs.edit().putInt("linger_minutes", v).apply() }

    val lingerMs: Long get() = lingerMinutes * 60_000L

    companion object {
        val LINGER_CHOICES = listOf(1, 5, 15)
    }
}
