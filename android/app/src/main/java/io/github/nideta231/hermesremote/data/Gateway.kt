package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A JSON-RPC error from Hermes, with its numeric code (4009 busy, 4018 use command.dispatch, …). */
class RpcException(val code: Int, message: String) : Exception(message)

/** Something Hermes sent without being asked. */
sealed interface Incoming {
    /** The socket is (re)open and announced as able to answer approvals: re-attach sessions now. */
    data object Open : Incoming

    /** `{"method":"event"}`: [sessionId] is the runtime id the event belongs to ("" for global ones). */
    data class Event(val type: String, val sessionId: String, val payload: JSONObject, val seq: Long?) : Incoming

    /** A question for the user (approval, clarify, …). Answer with [Gateway.reply] using [id]. */
    data class Request(val id: String, val method: String, val params: JSONObject) : Incoming
}

enum class GatewayState { CONNECTING, OPEN, RECONNECTING, UNAUTHORIZED, CLOSED }

/**
 * The phone's one live connection to Hermes: the bridge relays it to the same JSON-RPC socket the
 * desktop app uses, so events for every session the phone has open arrive here as they happen.
 *
 * Owns its own scope (not the ViewModel's) so the notification service can keep it alive while
 * the app is in the background. Reconnects with backoff until [close]; every reopen emits
 * [Incoming.Open] so the caller re-attaches whatever it was showing.
 */
class Gateway(
    private val http: OkHttpClient,
    val url: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var loop: Job? = null

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 4096)
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(GatewayState.CONNECTING)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    /** Why the last attempt failed, for the UI ("Hermes is not answering", …). */
    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            var backoff = 1_000L
            while (!closed) {
                val ended = CompletableDeferred<Int>()
                val opened = CompletableDeferred<Unit>()
                val ws = http.newWebSocket(Request.Builder().url(url).build(), Listener(opened, ended))
                socket = ws
                val code = runCatching {
                    withTimeout(20_000) { opened.await() }
                    // Without this Hermes treats the phone as a client that can't answer questions
                    // and fails approvals fast instead of showing them here.
                    call("client.capabilities", JSONObject().put("server_requests", true), 10_000)
                    _state.value = GatewayState.OPEN
                    lastError = null
                    backoff = 1_000L
                    _incoming.emit(Incoming.Open)
                    ended.await()
                }.getOrElse { t ->
                    lastError = t.message ?: t.javaClass.simpleName
                    ws.cancel()
                    if (ended.isCompleted) ended.await() else -1
                }
                socket = null
                failPending(IOException(lastError ?: "Connection to the PC closed"))
                if (closed) break
                if (code == CLOSE_UNAUTHORIZED) { _state.value = GatewayState.UNAUTHORIZED; break }
                _state.value = GatewayState.RECONNECTING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
            if (_state.value != GatewayState.UNAUTHORIZED) _state.value = GatewayState.CLOSED
        }
    }

    /** Ask the socket to reconnect now (network changed); a live socket is left alone. */
    fun nudge() {
        if (_state.value != GatewayState.OPEN) socket?.cancel()
    }

    /** Drop the socket even if it looks open (it may be half-dead after the phone slept); the loop reconnects. */
    fun forceReconnect() { socket?.cancel() }

    fun close() {
        closed = true
        socket?.close(1000, "bye")
        socket = null
        failPending(IOException("closed"))
        scope.cancel()
        _state.value = GatewayState.CLOSED
    }

    /** One JSON-RPC call. Throws [RpcException] for an error reply, [IOException] if the link drops. */
    suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val ws = socket ?: throw IOException("Not connected to your PC")
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        try {
            val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            if (!ws.send(frame.toString())) throw IOException("Not connected to your PC")
            val reply = withTimeout(timeoutMs) { waiter.await() }
            reply.optJSONObject("error")?.let { e ->
                throw RpcException(e.optInt("code", -1), e.str("message") ?: "Hermes refused $method")
            }
            return reply.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    /** Answer a question Hermes asked ([Incoming.Request]). False when the link is down. */
    fun reply(id: String, result: JSONObject): Boolean =
        socket?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()) ?: false

    private fun failPending(t: Throwable) {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private fun dispatch(text: String) {
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
        val method = frame.str("method")
        val id = frame.opt("id")
        when {
            method == null && id is Number -> pending[id.toLong()]?.complete(frame)
            method == "event" -> frame.optJSONObject("params")?.let { p ->
                _incoming.tryEmit(Incoming.Event(p.optString("type"), p.optString("session_id"),
                    p.optJSONObject("payload") ?: JSONObject(), if (p.has("seq")) p.optLong("seq") else null))
            }
            method != null && id is String -> _incoming.tryEmit(Incoming.Request(id, method, frame.optJSONObject("params") ?: JSONObject()))
        }
    }

    private inner class Listener(val opened: CompletableDeferred<Unit>, val ended: CompletableDeferred<Int>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            lastError = closeReason(code, reason)
            webSocket.close(1000, null)
            ended.complete(code)
            opened.completeExceptionally(IOException(lastError))
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(code) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastError = when {
                response?.code == 401 -> "This device was revoked or the token is wrong. Pair again."
                response?.code == 404 -> "Your PC runs an older bridge. Update it: run install.sh on the PC."
                t is javax.net.ssl.SSLException -> "That address isn't your PC (certificate mismatch). Nothing was sent."
                else -> t.message ?: "Connection to the PC failed"
            }
            ended.complete(if (response?.code == 401) CLOSE_UNAUTHORIZED else -1)
            opened.completeExceptionally(t)
        }
    }

    companion object {
        const val CLOSE_UNAUTHORIZED = 4401
        const val CLOSE_FORBIDDEN = 4403
        const val CLOSE_UNAVAILABLE = 1013

        fun closeReason(code: Int, reason: String): String = when (code) {
            CLOSE_UNAUTHORIZED -> "This device was revoked or the token is wrong. Pair again."
            CLOSE_FORBIDDEN -> reason.ifBlank { "The PC doesn't serve this network." }
            CLOSE_UNAVAILABLE -> reason.ifBlank { "Hermes is not running on the PC." }
            else -> reason.ifBlank { "Connection to the PC closed ($code)" }
        }
    }
}
