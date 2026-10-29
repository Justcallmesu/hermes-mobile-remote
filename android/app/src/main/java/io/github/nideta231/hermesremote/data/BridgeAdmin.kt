package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One doctor check: [level] ok / warn / fail, and the one-tap [fix] the bridge offers for it. */
data class DoctorCheck(val id: String, val level: String, val message: String, val fix: String?)

data class AdminNetwork(val id: String, val name: String, val since: Double?)
data class AdminDevice(val id: String, val name: String, val active: Boolean, val lastSeen: Double?, val createdAt: Double?, val isThis: Boolean)

data class BridgeAdminState(
    val checks: List<DoctorCheck> = emptyList(),
    val version: String? = null,
    val currentNetwork: String? = null,
    val currentNetworkId: String? = null,
    val currentTrusted: Boolean = false,
    val networks: List<AdminNetwork> = emptyList(),
    val devices: List<AdminDevice> = emptyList(),
    val logs: String? = null,
    val loading: Boolean = false,
    /** A fix or restart in flight (its name). */
    val busy: String? = null,
    val restarting: Boolean = false,
    val error: String? = null,
)

/**
 * Settings > PC bridge: what `hermes-remote-bridge doctor / trust / devices / revoke` and a service
 * restart do on the PC, from the phone. A restart drops the live socket for a few seconds; this waits
 * for the bridge to answer again and then brings the socket back at once ([onBridgeBack]).
 */
class BridgeAdmin(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
    private val onBridgeBack: () -> Unit,
) {
    private val _state = MutableStateFlow(BridgeAdminState())
    val state: StateFlow<BridgeAdminState> = _state.asStateFlow()

    fun clear() { _state.value = BridgeAdminState() }

    private fun fail(t: Throwable) {
        if (t is CancellationException) throw t
        _state.update { it.copy(loading = false, busy = null, error = describe(t)) }
    }

    fun refresh() {
        val c = client() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val doc = c.adminDoctor()
                val trust = c.adminTrust()
                val devs = c.adminDevices()
                apply(doc); applyTrust(trust); applyDevices(devs)
                _state.update { it.copy(loading = false) }
            } catch (t: Throwable) { fail(t) }
        }
    }

    private fun apply(doc: JSONObject) = _state.update { st ->
        st.copy(checks = doc.optJSONArray("checks").objects().map {
            DoctorCheck(it.optString("id"), it.optString("level"), it.optString("message"), it.str("fix")?.takeIf { f -> f.isNotBlank() })
        }, version = doc.str("running") ?: doc.str("version"))
    }

    private fun applyTrust(o: JSONObject) = _state.update { st ->
        val cur = o.optJSONObject("current")
        st.copy(currentNetwork = cur?.str("name"), currentNetworkId = cur?.str("id"), currentTrusted = cur?.optBoolean("trusted") == true,
            networks = o.optJSONArray("trusted").objects().map { AdminNetwork(it.optString("id"), it.optString("name"), it.dbl("since")) })
    }

    private fun applyDevices(o: JSONObject) = _state.update { st ->
        st.copy(devices = o.optJSONArray("devices").objects().map {
            AdminDevice(it.optString("id"), it.optString("name"), it.optBoolean("active"), it.dbl("last_seen_at"), it.dbl("created_at"), it.optBoolean("this"))
        }.filter { it.active })
    }

    fun fix(name: String) {
        if (name == "restart") return restart()
        val c = client() ?: return
        _state.update { it.copy(busy = name) }
        scope.launch {
            try { apply(c.adminFix(name)); _state.update { it.copy(busy = null) }; say("Fixed"); refresh() }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun trustCurrent() {
        val c = client() ?: return
        _state.update { it.copy(busy = "trust") }
        scope.launch {
            try { applyTrust(c.adminTrustNow()); _state.update { it.copy(busy = null) }; say("Network trusted: the phone can use Wi-Fi here"); refresh() }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun untrust(id: String) {
        val c = client() ?: return
        scope.launch {
            try { applyTrust(c.adminUntrust(id)); refresh() } catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun revoke(name: String) {
        val c = client() ?: return
        scope.launch {
            try { c.adminRevoke(name); say("$name can no longer connect"); applyDevices(c.adminDevices()) }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun loadLogs() {
        val c = client() ?: return
        scope.launch {
            try { val t = c.adminLogs(300).optString("text"); _state.update { it.copy(logs = t.ifBlank { "No log lines." }) } }
            catch (t: Throwable) { fail(t) }
        }
    }

    /** Restart the PC bridge, then wait for it and bring the connection straight back. */
    fun restart() {
        val c = client() ?: return
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true, busy = "restart", error = null) }
        scope.launch {
            try { c.adminRestart() } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(restarting = false, busy = null, error = describe(t)) }
                say(describe(t)); return@launch
            }
            say("Restarting the PC bridge…")
            var sawDown = false
            repeat(40) {
                delay(2_500)
                val c2 = client() ?: return@repeat
                val ok = runCatching { c2.adminDoctor() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (ok == null) { sawDown = true; return@repeat }
                // Answering again. If we never saw it go down, it may not have restarted yet: give it one more round.
                if (!sawDown && it < 3) return@repeat
                apply(ok)
                _state.update { it.copy(restarting = false, busy = null) }
                onBridgeBack()
                say("PC bridge restarted")
                refresh()
                return@launch
            }
            _state.update { it.copy(restarting = false, busy = null, error = "The bridge didn't answer yet; it keeps trying on the PC.") }
            onBridgeBack()
        }
    }
}
