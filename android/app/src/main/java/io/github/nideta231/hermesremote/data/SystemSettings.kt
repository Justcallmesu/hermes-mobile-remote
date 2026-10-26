package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemState(
    val components: List<ComponentStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val checkedAt: Long? = null,
    val approvalMode: String? = null,
    val approvalSaving: Boolean = false,
    /** Hermes' default model for new chats ("" when unknown or the bridge is too old). */
    val defaultModel: String = "",
    val defaultProvider: String = "",
    val defaultSaving: Boolean = false,
    /** Set while Hermes asks to confirm an expensive default. */
    val defaultConfirm: ModelConfirm? = null,
)

/**
 * Settings > PC: the bridge's component health, and the Hermes profile's approval mode and default
 * model. Answers that arrive after the user moved to another profile are dropped.
 */
class SystemSettings(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    /** Where the health rows come from: the bridge, or a gateway host's `/api/status`. */
    private val source: () -> SessionsSource? = client,
    private val profile: () -> String,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
    /** Hermes accepted [ModelOption] as the default: the model picker marks it. */
    private val onDefaultModel: (ModelOption) -> Unit,
) {
    private val _system = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()


    fun refresh() {
        val src = source() ?: return
        val c = client()  // null on a gateway host: no approval mode or default model there
        val asked = profile()
        _system.update { it.copy(loading = true) }
        scope.launch {
            try {
                val comps = src.status()
                val mode = c?.let { runCatching { it.approvalMode(asked) }.getOrNull() }
                val def = c?.let { runCatching { it.defaultModel(asked) }.getOrNull() }
                if (asked != profile()) return@launch
                _system.update {
                    it.copy(components = comps, loading = false, error = null, checkedAt = System.currentTimeMillis(),
                        approvalMode = mode ?: it.approvalMode,
                        defaultModel = def?.first ?: it.defaultModel, defaultProvider = def?.second ?: it.defaultProvider)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _system.update { it.copy(loading = false, error = describe(t), checkedAt = System.currentTimeMillis()) }
            }
        }
    }

    fun setApprovalMode(mode: String) {
        val c = client() ?: return
        if (_system.value.approvalMode == mode || _system.value.approvalSaving) return
        _system.update { it.copy(approvalSaving = true) }
        scope.launch {
            try {
                val applied = c.setApprovalMode(mode, profile())
                _system.update { it.copy(approvalMode = applied, approvalSaving = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _system.update { it.copy(approvalSaving = false) }
                say(describe(t))
            }
        }
    }

    /** Change Hermes' profile default model: every NEW chat (desktop, messaging, this app) uses it. */
    fun setDefaultModel(option: ModelOption, confirmed: Boolean = false) {
        val c = client() ?: return
        if (_system.value.defaultSaving) return
        _system.update { it.copy(defaultSaving = true, defaultConfirm = null) }
        scope.launch {
            try {
                val res = c.setDefaultModel(option.id, option.provider, confirmed, profile())
                if (res.optBoolean("confirm_required")) {
                    val msg = res.optString("confirm_message").ifBlank { "This model is expensive. Make it the default anyway?" }
                    _system.update { it.copy(defaultSaving = false, defaultConfirm = ModelConfirm(option, msg)) }
                    return@launch
                }
                _system.update { it.copy(defaultSaving = false, defaultModel = res.optString("model", option.id),
                    defaultProvider = res.optString("provider", option.provider)) }
                onDefaultModel(option)
                say("New chats now use ${option.label}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _system.update { it.copy(defaultSaving = false) }
                say(describe(t))
            }
        }
    }

    fun dismissDefaultConfirm() = _system.update { it.copy(defaultConfirm = null) }

    /** Another PC, or none: nothing shown belongs to it. */
    fun clear() { _system.value = SystemState() }

    /** Another Hermes profile: its approval mode and default model are its own; the PC's health stays. */
    fun forgetProfileValues() =
        _system.update { it.copy(approvalMode = null, defaultModel = "", defaultProvider = "", defaultConfirm = null) }
}
