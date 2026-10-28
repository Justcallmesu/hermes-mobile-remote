package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The end of a child's live transcript (`subagent.tail`): Hermes keeps the last 16 KB. */
data class SubagentTail(
    /** False while the child has no transcript yet, or once it ended and Hermes let it go. */
    val available: Boolean = false,
    val text: String = "",
    /** Older output was cut off; only the end is shown. */
    val truncated: Boolean = false,
) {
    companion object {
        fun parse(o: JSONObject) = SubagentTail(o.optBoolean("available"), o.str("text").orEmpty(), o.optBoolean("truncated"))
    }
}

/** How Hermes took a steering instruction (`subagent.steer`). */
enum class SteerOutcome {
    /** Queued for the child's next tool round. Not a delivery receipt: a child past its last tool may miss it. */
    QUEUED,
    /** The child ended, no longer takes steering, or isn't this chat's. */
    REJECTED;

    val message: String get() = when (this) {
        QUEUED -> "Sent. The subagent reads it after its current step."
        REJECTED -> "That subagent no longer takes instructions."
    }

    companion object {
        fun parse(o: JSONObject) = if (o.str("status") == "queued") QUEUED else REJECTED
    }
}

/**
 * Reading a running child's output and steering it, for the subagent rows of the open chat. Kept
 * out of the ViewModel; it only needs a way to call Hermes on the open chat and its runtime id.
 */
class SubagentOutput(
    private val scope: CoroutineScope,
    /** One RPC on the open chat, given its runtime id (re-attaching a reaped runtime if needed). */
    private val rpc: suspend (method: String, params: (String) -> JSONObject) -> JSONObject,
    /** The open chat's stored id: a poll ends when another chat opens. */
    private val chatId: () -> String?,
    /** The open chat is attached on a connected socket; polls skip a round otherwise rather than re-attach. */
    private val live: () -> Boolean,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    /**
     * Poll a child's transcript tail into [onTail] until the caller is cancelled (row closed,
     * child ended, chat off screen) or another chat opens. A failed read keeps what is shown and
     * tries again: the socket may be reconnecting.
     */
    suspend fun follow(subagentId: String, onTail: (SubagentTail) -> Unit) {
        val chat = chatId() ?: return
        while (chatId() == chat) {
            if (live()) try {
                val tail = SubagentTail.parse(rpc("subagent.tail") { params(it, subagentId) })
                if (chatId() == chat) onTail(tail)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
            delay(POLL_MS)
        }
    }

    /** Send [text] to a running child; [onDone] gets true once Hermes queued it. */
    fun steer(subagentId: String, text: String, onDone: (Boolean) -> Unit = {}) {
        val instruction = text.trim().take(MAX_STEER_CHARS)
        if (instruction.isEmpty()) return
        scope.launch {
            val outcome = try {
                SteerOutcome.parse(rpc("subagent.steer") { params(it, subagentId).put("text", instruction) })
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(describe(t))
                onDone(false)
                return@launch
            }
            say(outcome.message)
            onDone(outcome == SteerOutcome.QUEUED)
        }
    }

    private fun params(sessionId: String, subagentId: String) =
        JSONObject().put("session_id", sessionId).put("subagent_id", subagentId)

    companion object {
        const val POLL_MS = 2_000L
        /** The bridge refuses longer steering text (relay.MAX_STEER_CHARS). */
        const val MAX_STEER_CHARS = 4_000
    }
}
