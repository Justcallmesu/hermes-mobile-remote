package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Gateway
import io.github.nideta231.hermesremote.data.GatewayAuthError
import io.github.nideta231.hermesremote.data.GatewayAuthException
import io.github.nideta231.hermesremote.data.GatewayState
import io.github.nideta231.hermesremote.data.SocketUrlProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GatewayUrlProviderTest {
    private val server = MockWebServer()
    private val okHermes = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val id = org.json.JSONObject(text).opt("id") ?: return
            webSocket.send(org.json.JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", org.json.JSONObject()).toString())
        }
    }

    @Before fun setUp() { server.start(InetAddress.getByName("127.0.0.1"), 0) }
    @After fun tearDown() { server.shutdown() }

    private class FakeProvider(val base: String, val onRefresh: () -> Unit = {}) : SocketUrlProvider {
        override val gatewayMode = true
        val urls = CopyOnWriteArrayList<String>()
        val refreshes = AtomicInteger()
        override suspend fun url(): String = "$base/api/ws?ticket=t${urls.size + 1}".also { urls += it }
        override suspend fun refresh() { refreshes.incrementAndGet(); onRefresh() }
    }

    private fun gateway(p: SocketUrlProvider) = Gateway(OkHttpClient(), "gw", p)
    private fun base() = "ws://127.0.0.1:${server.port}"

    @Test fun bridgeModeConnectsToTheFixedUrl() = runBlocking<Unit> {
        server.enqueue(MockResponse().withWebSocketUpgrade(okHermes))
        val g = Gateway(OkHttpClient(), base() + "/v1/ws")
        g.start()
        try {
            withTimeout(5_000) { g.state.first { it == GatewayState.OPEN } }
            assertEquals("/v1/ws", server.takeRequest().path)
        } finally { g.close() }
    }

    @Test fun gatewayModeAsksTheProviderOnEveryConnect() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().withWebSocketUpgrade(okHermes)) }
        val p = FakeProvider(base())
        val g = gateway(p)
        g.start()
        try {
            withTimeout(10_000) {
                while (p.urls.size < 3) {
                    if (g.state.value == GatewayState.OPEN) g.forceReconnect()
                    delay(100)
                }
            }
            assertEquals(3, p.urls.take(3).toSet().size)
            assertEquals("/api/ws?ticket=t1", server.takeRequest().path)
        } finally { g.close() }
    }

    @Test fun a401RefreshesOnceThenRetriesAndASecond401IsUnauthorized() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val p = FakeProvider(base())
        val g = gateway(p)
        g.start()
        try {
            withTimeout(5_000) { g.state.first { it == GatewayState.UNAUTHORIZED } }
            assertEquals(1, p.refreshes.get())
            assertEquals(2, p.urls.size)
            assertEquals(2, server.requestCount)
        } finally { g.close() }
    }

    @Test fun aRefreshThatIsUnavailableDoesNotEndInUnauthorized() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().withWebSocketUpgrade(okHermes))
        val p = FakeProvider(base(), onRefresh = {
            if (server.requestCount < 2) throw GatewayAuthException(GatewayAuthError.RETRY_LATER, "gateway unavailable")
        })
        val g = gateway(p)
        g.start()
        try {
            withTimeout(10_000) { g.state.first { it == GatewayState.OPEN } }
            assertTrue(p.refreshes.get() >= 1)
        } finally { g.close() }
    }

    @Test fun unauthorizedMessageSaysSignInForGatewayAndPairForBridge() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val gw = gateway(FakeProvider(base()))
        gw.start()
        try {
            withTimeout(5_000) { gw.state.first { it == GatewayState.UNAUTHORIZED } }
            assertTrue(gw.lastError!!.contains("Sign in again"))
        } finally { gw.close() }

        server.enqueue(MockResponse().setResponseCode(401))
        val br = Gateway(OkHttpClient(), base() + "/v1/ws")
        br.start()
        try {
            withTimeout(5_000) { br.state.first { it == GatewayState.UNAUTHORIZED } }
            // Bridge wording is untouched (the bridge path never says "sign in").
            assertTrue(!br.lastError!!.contains("Sign in"))
            assertNotEquals(gw.lastError, br.lastError)
        } finally { br.close() }
    }
}
