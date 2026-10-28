package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ConnectionPlan
import io.github.nideta231.hermesremote.data.GatewayCredentialStore
import io.github.nideta231.hermesremote.data.GatewayCredentials
import io.github.nideta231.hermesremote.data.GatewayState
import io.github.nideta231.hermesremote.data.HostConnection
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.HostLink
import io.github.nideta231.hermesremote.data.HostLinking
import io.github.nideta231.hermesremote.data.HttpReply
import io.github.nideta231.hermesremote.data.HttpTransport
import io.github.nideta231.hermesremote.data.Root
import io.github.nideta231.hermesremote.data.buildGateway
import io.github.nideta231.hermesremote.data.decideConnection
import io.github.nideta231.hermesremote.data.gatewayAuthFor
import io.github.nideta231.hermesremote.data.rootScreen
import io.github.nideta231.hermesremote.data.viaLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HostConnectionTest {
    private val creds = GatewayCredentials("https://gw.example", "A1", "R1", 100L, "google")

    @Test fun decisionFollowsHostKindAndTokens() {
        assertEquals(HostConnection.BridgePairing, decideConnection(HostKind.BRIDGE, null))
        assertEquals(HostConnection.GatewayLogin(creds), decideConnection(HostKind.GATEWAY, creds))
        assertEquals(HostConnection.NeedsSignIn, decideConnection(HostKind.GATEWAY, null))
    }

    @Test fun linkedHostPrefersBridgeAndFallsBackToGateway() {
        val gw = HostConnection.GatewayLogin(creds)
        val up = decideConnection(HostKind.BRIDGE, creds, linked = true, bridgeReachable = true)
        assertEquals(ConnectionPlan(HostConnection.BridgePairing, gw), up)
        val down = decideConnection(HostKind.BRIDGE, creds, linked = true, bridgeReachable = false)
        assertEquals(ConnectionPlan(gw, HostConnection.BridgePairing), down)
        assertEquals(ConnectionPlan(HostConnection.NeedsSignIn, null), decideConnection(HostKind.BRIDGE, null, linked = true, bridgeReachable = false))
        // unlinked hosts keep the old rule
        assertEquals(ConnectionPlan(HostConnection.BridgePairing, null), decideConnection(HostKind.BRIDGE, null, linked = false, bridgeReachable = false))
        assertEquals(ConnectionPlan(HostConnection.NeedsSignIn, null), decideConnection(HostKind.GATEWAY, null, linked = false, bridgeReachable = true))
    }

    @Test fun bridgeComingBackIsPickedAgainButNeverFlapsWithin30s() {
        val gw = HostConnection.GatewayLogin(creds)
        fun back(since: Long) = decideConnection(HostKind.BRIDGE, creds, linked = true, bridgeReachable = true, current = gw, sinceSwitchMs = since).connection
        assertEquals(gw, back(5_000))
        assertEquals(gw, back(29_999))
        assertEquals(HostConnection.BridgePairing, back(30_000))
    }

    @Test fun linkedPlanIsTheSameFromEitherSide() {
        val link = HostLink("b1", "g1")
        val links = listOf(HostLink("b0", "g0"), link)
        val plans = listOf("b1" to HostKind.BRIDGE, "g1" to HostKind.GATEWAY).map { (id, kind) ->
            val l = HostLinking.linkOf(links, id)
            assertEquals(link, l)
            decideConnection(kind, creds, linked = l != null, bridgeReachable = true)
        }
        assertEquals(plans[0], plans[1])
        assertEquals(null, HostLinking.linkOf(links, "other"))
    }

    @Test fun linkedRowSaysViaBridgeFromEitherSide() {
        val links = listOf(HostLink("b1", "g1"))
        for ((id, kind) in listOf("b1" to HostKind.BRIDGE, "g1" to HostKind.GATEWAY)) {
            val plan = decideConnection(kind, creds, linked = HostLinking.linkOf(links, id) != null, bridgeReachable = true)
            assertEquals("via Bridge", viaLabel(plan.connection))
        }
    }

    @Test fun connectionRowNamesTheLiveWay() {
        assertEquals("via Bridge", viaLabel(HostConnection.BridgePairing))
        assertEquals("via Gateway", viaLabel(HostConnection.GatewayLogin(creds)))
        assertEquals(null, viaLabel(HostConnection.NeedsSignIn))
    }

    @Test fun rootShowsAppForGatewayHostAndSignInWhenSignedOut() {
        assertEquals(Root.APP, rootScreen(paired = false, gatewaySignedOut = false, adding = false))
        assertEquals(Root.SIGN_IN, rootScreen(paired = false, gatewaySignedOut = true, adding = false))
        assertEquals(Root.PAIR, rootScreen(paired = false, gatewaySignedOut = null, adding = false))
        assertEquals(Root.PAIR, rootScreen(paired = false, gatewaySignedOut = false, adding = true))
        assertEquals(Root.APP, rootScreen(paired = true, gatewaySignedOut = null, adding = false))
    }

    @Test fun refreshedTokensAreWrittenBackToTheStore() = runBlocking<Unit> {
        val prefs = MemPrefs()
        val store = GatewayCredentialStore(prefs, ReverseCipher)
        store.save(creds)
        val http = object : HttpTransport {
            val bodies = mutableListOf<String>()
            override suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply {
                bodies += jsonBody
                return HttpReply(200, """{"access_token":"A2","refresh_token":"R2","expires_at":200}""")
            }
        }
        gatewayAuthFor(creds, store, http).refresh()
        assertTrue(http.bodies.single().contains("\"refresh_token\":\"R1\""))
        assertTrue(http.bodies.single().contains("\"provider\":\"google\""))
        // a restart: a fresh store over the same prefs holds the rotated token
        val reloaded = GatewayCredentialStore(prefs, ReverseCipher).load()!!
        assertEquals(GatewayCredentials("https://gw.example", "A2", "R2", 200L, "google"), reloaded)
    }

    @Test fun gatewayHostConnectsWithATicketUrl() = runBlocking<Unit> {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = org.json.JSONObject(text).opt("id") ?: return
                    webSocket.send(org.json.JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", org.json.JSONObject()).toString())
                }
            }))
            val base = "http://127.0.0.1:${server.port}"
            val http = object : HttpTransport {
                override suspend fun post(url: String, bearer: String?, jsonBody: String) = HttpReply(200, """{"ticket":"tk9"}""")
            }
            val c = creds.copy(baseUrl = base)
            val g = buildGateway(OkHttpClient(), c.baseUrl, gatewayAuthFor(c, GatewayCredentialStore(MemPrefs(), ReverseCipher), http))
            assertEquals(base, g.url) // identity is the base URL, not a ticket URL
            g.start()
            try {
                withTimeout(5_000) { g.state.first { it == GatewayState.OPEN } }
                assertEquals("/api/ws?ticket=tk9", server.takeRequest().path)
            } finally { g.close() }
        } finally { server.shutdown() }
    }
}
