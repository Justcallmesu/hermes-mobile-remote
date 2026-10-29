package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.GatewayState
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.Incoming
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.data.parseApproval
import io.github.nideta231.hermesremote.data.parseCatalog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's real socket, reducer and history code against the real bridge in front of the demo
 * backend (demo/mock_hermes.py). No model calls, so CI runs it on every push: `demo/e2e.sh` starts
 * the stack, pairs a device and sets HERMES_REMOTE_DEMO_E2E to its credentials file.
 *
 * It checks the event contract the app depends on: streaming deltas, tool events, an approval asked
 * over the socket and answered by id, the transcript Hermes stores, the session list, pins and the
 * model catalog. [LiveBridgeTest] does the same against a real Hermes on a maintainer PC.
 */
class DemoBridgeTest {
    private val creds = System.getenv("HERMES_REMOTE_DEMO_E2E")?.takeIf { it.isNotBlank() }?.let { JSONObject(File(it).readText()) }

    // The demo bridge listens on loopback only; the host guard is the only thing relaxed here.
    private fun client() = BridgeClient(Pairing(creds!!.getString("url"), creds.getString("device"), creds.getString("token")),
        hostGuard = { host, https -> host == "127.0.0.1" || io.github.nideta231.hermesremote.data.Transport.allowsToken(host, https, false) })

    private suspend fun <T> withSocket(block: suspend (io.github.nideta231.hermesremote.data.Gateway) -> T): T {
        val g = client().gateway()
        g.start()
        try {
            withTimeout(20_000) { g.state.first { it == GatewayState.OPEN } }
            return block(g)
        } finally { g.close() }
    }

    @Test fun bridgeAndBackendAnswer() = runBlocking<Unit> {
        assumeTrue("run demo/e2e.sh", creds != null)
        val c = client()
        assertEquals(creds!!.getString("device"), c.me().getJSONObject("device").getString("name"))
        assertTrue("hermes ok", c.status().first { it.key == "hermes" }.ok)
        assertTrue("seeded sessions listed", c.sessions(100, 0).first.isNotEmpty())
    }

    @Test fun turnWithApprovalStreamsAndIsStored() = runBlocking<Unit> {
        assumeTrue("run demo/e2e.sh", creds != null)
        val c = client()
        withSocket { g ->
            val created = g.call("session.create", JSONObject().put("source", "desktop").put("cols", 96), 30_000)
            val rid = created.getString("session_id")
            val stored = created.optString("stored_session_id", rid)
            try {
                val incoming = async(start = CoroutineStart.UNDISPATCHED) {
                    g.incoming
                        .filter { (it is Incoming.Event && it.sessionId == rid) || it is Incoming.Request }
                        .transformWhile { inc ->
                            emit(inc)
                            // Answer the approval the moment it arrives, as the approval dock does.
                            if (inc is Incoming.Request && inc.method == "approval") {
                                assertTrue(g.reply(inc.id, JSONObject().put("choice", "once")))
                            }
                            !(inc is Incoming.Event && inc.type == "message.complete")
                        }.toList()
                }
                // "run " makes the scripted agent ask for approval before its terminal tool.
                g.call("prompt.submit", JSONObject().put("session_id", rid).put("text", "Please run git status"))
                val all = withTimeout(30_000) { incoming.await() }

                val approvals = all.filterIsInstance<Incoming.Request>().filter { it.method == "approval" }
                assertEquals("one approval asked", 1, approvals.size)
                val req = parseApproval(approvals[0].id, approvals[0].params)
                assertEquals(rid, req.sessionId)
                assertTrue("approval names its command", !req.command.isNullOrBlank())
                assertTrue("approval offers deny", "deny" in req.choices)

                val events = all.filterIsInstance<Incoming.Event>()
                val trace = events.joinToString("\n") { "${it.type} ${it.payload.toString().take(160)}" }
                assertTrue("streamed deltas:\n$trace", events.count { it.type == "message.delta" } > 1)
                var items: List<ChatItem> = listOf(ChatItem.User("u", "x"))
                events.forEach { items = LiveReducer.apply(items, it.type, it.payload, "${it.seq}") }
                val reply = items.filterIsInstance<ChatItem.Assistant>().lastOrNull()
                assertTrue("reply shown:\n$trace", reply != null && reply.text.contains("relay.py") && !reply.streaming)
                assertTrue("terminal tool ran after approval", items.any { it is ChatItem.Tool && it.name == "terminal" })
                assertTrue("no tool left running", items.filterIsInstance<ChatItem.Tool>().none { it.status == ToolStatus.RUNNING })

                // The desktop opens the same session: resume returns what Hermes stored.
                val resumed = g.call("session.resume", JSONObject().put("session_id", stored).put("cols", 96), 30_000)
                val history = HistoryMapper.map(resumed.optJSONArray("messages"))
                assertTrue(history.any { it is ChatItem.Tool && it.name == "terminal" })
                assertTrue(history.filterIsInstance<ChatItem.Assistant>().last().text.contains("relay.py"))
                assertTrue("listed like the desktop sidebar", c.sessions(100, 0).first.any { it.id == stored })
            } finally {
                runCatching { g.call("session.close", JSONObject().put("session_id", rid)) }
                g.call("session.delete", JSONObject().put("session_id", stored))
            }
        }
    }

    @Test fun deniedApprovalSkipsTheTool() = runBlocking<Unit> {
        assumeTrue("run demo/e2e.sh", creds != null)
        withSocket { g ->
            val created = g.call("session.create", JSONObject().put("source", "desktop"), 30_000)
            val rid = created.getString("session_id")
            val stored = created.optString("stored_session_id", rid)
            try {
                val incoming = async(start = CoroutineStart.UNDISPATCHED) {
                    g.incoming
                        .filter { (it is Incoming.Event && it.sessionId == rid) || it is Incoming.Request }
                        .transformWhile { inc ->
                            emit(inc)
                            if (inc is Incoming.Request && inc.method == "approval") g.reply(inc.id, JSONObject().put("choice", "deny"))
                            !(inc is Incoming.Event && inc.type == "message.complete")
                        }.toList()
                }
                g.call("prompt.submit", JSONObject().put("session_id", rid).put("text", "run rm -rf build"))
                val events = withTimeout(30_000) { incoming.await() }.filterIsInstance<Incoming.Event>()
                assertTrue("no tool after deny", events.none { it.type == "tool.start" })
                assertTrue("request closed", events.any { it.type == "request.cancel" })
            } finally {
                runCatching { g.call("session.close", JSONObject().put("session_id", rid)) }
                g.call("session.delete", JSONObject().put("session_id", stored))
            }
        }
    }

    @Test fun modelCatalogAndPins() = runBlocking<Unit> {
        assumeTrue("run demo/e2e.sh", creds != null)
        val c = client()
        withSocket { g ->
            val cat = parseCatalog(g.call("model.options", JSONObject(), 30_000))
            assertTrue(cat.options.isNotEmpty())
            assertTrue(cat.options.all { it.provider.isNotBlank() && it.id.isNotBlank() })

            val created = g.call("session.create", JSONObject().put("source", "desktop"), 30_000)
            val rid = created.getString("session_id")
            val stored = created.optString("stored_session_id", rid)
            try {
                g.call("session.title", JSONObject().put("session_id", rid).put("title", "pin-probe"))
                c.setPinned(stored, true)
                assertTrue(c.sessions(100, 0).first.first { it.id == stored }.pinned)
                c.setPinned(stored, false)
                assertTrue(!c.sessions(100, 0).first.first { it.id == stored }.pinned)
            } finally {
                runCatching { g.call("session.close", JSONObject().put("session_id", rid)) }
                g.call("session.delete", JSONObject().put("session_id", stored))
            }
        }
    }
}
