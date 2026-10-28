package io.github.nideta231.hermesremote.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.delay
import org.json.JSONObject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The process-wide live connection, shared by the chat screen and the notification service.
 *
 * It lives outside the ViewModel on purpose: when the app goes to the background the activity
 * (and its ViewModel) may be destroyed, but a turn still running on the PC must still be able to
 * notify "finished" or "needs your approval". [WatchService] keeps the process alive while
 * [busy] is non-empty; this object turns the socket's events into those notifications.
 */
object LiveLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    @Volatile var gateway: Gateway? = null
        private set

    /** [profile]: the Hermes profile the session belongs to, so a notification opens it there. */
    data class Watched(val storedId: String, val title: String?, val profile: String? = null)

    /** Runtime id → session the phone has open or started. Only these notify. */
    private val watched = ConcurrentHashMap<String, Watched>()

    /** Runtime ids loaded from disk after a process restart: resumed on the first Open whatever [busy] says. */
    private val toResume = ConcurrentHashMap.newKeySet<String>()
    private var store: WatchedStore? = null
    private var heartbeat: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Runtime ids with a turn running or a question waiting: what keeps [WatchService] up. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** Keys of background work a session still owns (delegation, loop, heartbeat): also keeps [WatchService] up. */
    private val _backgroundWork = MutableStateFlow<Set<String>>(emptySet())
    val backgroundWork: StateFlow<Set<String>> = _backgroundWork.asStateFlow()

    fun setBackgroundWork(key: String, on: Boolean) = _backgroundWork.update { if (on) it + key else it - key }

    /** After the system killed the process: open the socket again for the active host, from its saved login. */
    fun restore(context: Context) {
        if (gateway != null) return
        val app = context.applicationContext
        // The process was killed: whatever the phone was watching is resumed on the first Open.
        val saved = store(app).load()
        if (saved.isNotEmpty()) { watched.putAll(saved); toResume += saved.keys }
        val pr = ProfileStore(app).active()
        val store = if (pr.kind == HostKind.GATEWAY) GatewayCredentialStore(app, pr.id) else null
        when (val how = decideConnection(pr.kind, store?.load())) {
            HostConnection.BridgePairing -> CredentialStore(app, ProfileStore.pairingFile(pr.id)).load()?.let { connect(app, BridgeClient(it)) }
            is HostConnection.GatewayLogin ->
                connectGateway(app, how.credentials, gatewayAuthFor(how.credentials, store!!, OkHttpTransport()))
            HostConnection.NeedsSignIn -> {}
        }
    }

    /** Reuse the live socket when it already points at [client]'s address; otherwise replace it. */
    fun connect(context: Context, client: BridgeClient): Gateway = adopt(context, client.gateway())

    /** The same for a gateway host: its socket takes a fresh ticket from [auth] on every connect. */
    fun connectGateway(context: Context, creds: GatewayCredentials, auth: GatewayAuth): Gateway =
        adopt(context, buildGateway(SharedHttp.client, creds.baseUrl, auth))

    private fun adopt(context: Context, fresh: Gateway): Gateway {
        gateway?.let { g ->
            if (g.url == fresh.url && g.state.value != GatewayState.CLOSED && g.state.value != GatewayState.UNAUTHORIZED) return g
        }
        close()
        gateway = fresh
        fresh.start()
        val app = context.applicationContext
        store(app)
        collector = scope.launch { fresh.incoming.collect { onIncoming(app, it) } }
        heartbeat = scope.launch {
            while (true) {
                delay(ReattachPolicy.PING_INTERVAL_MS)
                ReattachPolicy.heartbeat(link(fresh), background = !visible())
            }
        }
        if (networkCallback == null) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { reattach() } }
            }
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback!!) }
        }
        return fresh
    }

    /** The one reattach: a network change and a service start both come here. */
    fun reattach() {
        val g = gateway ?: return
        scope.launch { ReattachPolicy.reattach(link(g)) }
    }

    private fun link(g: Gateway) = object : ReattachLink {
        override val isOpen get() = g.state.value == GatewayState.OPEN
        override fun forceReconnect() = g.forceReconnect()
        override fun nudge() = g.nudge()
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long) = g.call(method, params, timeoutMs)
    }

    private fun store(app: Context): WatchedStore = store ?: WatchedStore(app).also { store = it }

    private fun saveWatched() { store?.save(watched) }

    fun close() {
        collector?.cancel()
        collector = null
        heartbeat?.cancel()
        heartbeat = null
        gateway?.close()
        gateway = null
        _busy.value = emptySet()
        _backgroundWork.value = emptySet()
    }

    fun watch(runtimeId: String, storedId: String, title: String?, profile: String? = null) {
        val w = Watched(storedId, title, profile)
        if (watched.put(runtimeId, w) != w) saveWatched()
    }

    fun retitle(runtimeId: String, title: String) {
        watched[runtimeId]?.let { watched[runtimeId] = it.copy(title = title); saveWatched() }
    }

    fun storedIdOf(runtimeId: String): String? = watched[runtimeId]?.storedId

    private fun setBusy(sid: String, on: Boolean) = _busy.update { if (on) it + sid else it - sid }

    private fun visible(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun onIncoming(app: Context, inc: Incoming) {
        when (inc) {
            is Incoming.Open -> {
                val g = gateway ?: return
                val want = _busy.value + toResume
                toResume.clear()
                scope.launch {
                    ReattachPolicy.resumeWatched(link(g), watched.toMap(), want).forEach { (old, new) ->
                        if (old != new) watched.remove(old)?.let { watched[new] = it; saveWatched() }
                    }
                }
            }
            is Incoming.Event -> {
                val sid = inc.sessionId
                when (inc.type) {
                    "message.start" -> if (watched.containsKey(sid)) setBusy(sid, true)
                    "message.complete" -> {
                        setBusy(sid, false)
                        val w = watched[sid] ?: return
                        if (!visible()) {
                            Notifier.postTurnFinished(app, "$sid:${inc.seq ?: System.nanoTime()}", w.storedId, w.profile, w.title,
                                inc.payload.str("status") ?: "complete",
                                inc.payload.str("text") ?: inc.payload.str("error"))
                        }
                    }
                    "session.title" -> inc.payload.str("title")?.let { retitle(sid, it) }
                    "request.cancel" -> inc.payload.str("id")?.let { Notifier.clearQuestion(app, it) }
                }
            }
            is Incoming.Request -> {
                val sid = inc.params.str("session_id") ?: return
                val w = watched[sid] ?: return
                if (visible()) return
                when (inc.method) {
                    "approval" -> Notifier.postApprovalNeeded(app, inc.id, w.storedId, w.profile, w.title, parseApproval(inc.id, inc.params))
                    "clarify" -> Notifier.postQuestion(app, inc.id, w.storedId, w.profile, w.title,
                        parseClarify(inc.id, inc.params).questions.firstOrNull()?.question)
                    else -> parseSecret(inc.id, inc.method, inc.params)?.let { r ->
                        Notifier.postQuestion(app, inc.id, w.storedId, w.profile, w.title, "${r.title}: open the chat to answer.")
                    }
                }
            }
        }
    }

    /** A question was answered here: stop alerting about it. */
    fun answered(context: Context, requestId: String) = Notifier.clearQuestion(context, requestId)
}
