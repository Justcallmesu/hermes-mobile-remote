package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The PC bridge's own release and whether a newer one can be installed from the phone. */
data class BridgeUpdateState(
    val current: String? = null,
    val latest: String? = null,
    val available: Boolean = false,
    val canUpdate: Boolean = false,
    /** Why it can't update itself (desktop-app install, local changes, offline...). */
    val reason: String? = null,
    val checking: Boolean = false,
    /** Started from here and not finished yet. */
    val updating: Boolean = false,
    /** Outcome of the last update (\"Updated to v1.29.0\", or the failure). */
    val message: String? = null,
    val failed: Boolean = false,
    /** The PC already has newer code on disk but the running bridge was never restarted onto it. */
    val restartNeeded: Boolean = false,
) {
    companion object {
        fun parse(o: JSONObject): BridgeUpdateState {
            val st = o.optJSONObject("status")
            val running = st?.str("state") == "running"
            return BridgeUpdateState(current = o.str("current"), latest = o.str("latest"), available = o.optBoolean("available"),
                canUpdate = o.optBoolean("can_update"), reason = o.str("reason"), updating = running,
                message = when (st?.str("state")) {
                    "done" -> st.str("to")?.let { "Last update: $it" }
                    "failed" -> "Last update failed: ${st.str("error").orEmpty()}"
                    "running" -> "Updating (${st.str("step") ?: "starting"})…"
                    else -> null
                }, failed = st?.str("state") == "failed", restartNeeded = o.optBoolean("restart_needed"))
        }
    }
}

/**
 * Settings > Bridge update. [start] asks the bridge to update itself; the bridge restarts at the end
 * (the socket drops for a few seconds and comes back on its own, with the same pairing), so this
 * polls the check until the reported release changes.
 */
class BridgeUpdater(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
    /** The bridge is back (updated or rolled back): reconnect the live socket now and reload what it serves. */
    private val onBridgeBack: () -> Unit = {},
) {
    private val _state = MutableStateFlow(BridgeUpdateState())
    val state: StateFlow<BridgeUpdateState> = _state.asStateFlow()
    private var poll: Job? = null

    fun clear() { poll?.cancel(); _state.value = BridgeUpdateState() }

    fun check(force: Boolean = false) {
        val c = client() ?: return
        _state.update { it.copy(checking = true) }
        scope.launch {
            try {
                val s = BridgeUpdateState.parse(c.bridgeUpdateCheck(force))
                _state.update { s.copy(updating = s.updating || (it.updating && poll?.isActive == true)) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(checking = false, reason = describe(t)) }
            }
        }
    }

    fun start() {
        val c = client() ?: return
        val from = _state.value.current
        _state.update { it.copy(updating = true, message = "Starting the update…", failed = false) }
        scope.launch {
            try {
                c.startBridgeUpdate()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(updating = false, message = describe(t), failed = true) }
                return@launch
            }
            say("Updating the bridge. It restarts in a minute; your pairing stays.")
            poll?.cancel()
            poll = scope.launch {
                var sawDown = false
                // Up to ~6 minutes: fetch + uv sync + restart + health check, with a rollback on failure.
                repeat(90) {
                    delay(4_000)
                    val c2 = client() ?: return@repeat
                    val now = try { BridgeUpdateState.parse(c2.bridgeUpdateCheck(true)) }
                        catch (t: Throwable) { if (t is CancellationException) throw t; sawDown = true; null }
                        ?: return@repeat  // restarting: keep asking, the bridge comes back on its own
                    if (sawDown) onBridgeBack()  // the restart dropped the socket: bring it straight back
                    sawDown = false
                    if (!now.updating) {
                        _state.value = now
                        onBridgeBack()
                        say(when {
                            now.failed -> now.message ?: "Bridge update failed; the bridge kept running."
                            now.current != from -> "Bridge updated to ${now.current}"
                            else -> "Bridge is running ${now.current ?: ""}".trim()
                        })
                        return@launch
                    }
                    _state.update { now.copy(updating = true) }
                }
                _state.update { it.copy(updating = false, message = "No answer from the update; the bridge may still be finishing.") }
                onBridgeBack()
            }
        }
    }
}
