package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.GatewayAuth
import io.github.nideta231.hermesremote.data.GatewayTokens
import io.github.nideta231.hermesremote.data.GatewayUrl
import io.github.nideta231.hermesremote.data.HttpReply
import io.github.nideta231.hermesremote.data.HttpTransport
import io.github.nideta231.hermesremote.data.LoopbackException
import io.github.nideta231.hermesremote.data.LoopbackListener
import io.github.nideta231.hermesremote.data.OkHttpTransport
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewaySignInTest {
    private fun get(url: String): Int = try {
        (URL(url).openConnection() as HttpURLConnection).run { responseCode.also { disconnect() } }
    } catch (e: java.io.IOException) { -1 }

    private fun wait(l: LoopbackListener) = CompletableFuture.supplyAsync { runCatching { l.awaitCode() } }

    @Test fun rightStateReturnsTheCodeAndTheListenerClosesAfterOneRequest() {
        LoopbackListener("st4te").use { l ->
            val port = l.port
            val result = wait(l)
            assertEquals(200, get("${l.redirectUri}?code=abc&state=st4te"))
            assertEquals("abc", result.get().getOrThrow())
            assertEquals(-1, get("http://127.0.0.1:$port/cb?code=again&state=st4te"))
        }
    }

    @Test fun wrongStateDoesNotReturnACodeAndClosesToo() {
        LoopbackListener("st4te").use { l ->
            val result = wait(l)
            assertEquals(400, get("${l.redirectUri}?code=abc&state=evil"))
            assertTrue(result.get().exceptionOrNull() is LoopbackException)
            assertEquals(-1, get("${l.redirectUri}?code=abc&state=st4te"))
        }
    }

    @Test fun timeoutEndsTheWaitAndCloses() {
        LoopbackListener("st4te", timeoutMs = 200).use { l ->
            val r = runCatching { l.awaitCode() }
            assertTrue(r.exceptionOrNull() is LoopbackException)
            assertEquals(-1, get("${l.redirectUri}?code=abc&state=st4te"))
        }
    }

    @Test fun httpsAnywhereAndPlainHttpOnlyOnTailnetOrLan() {
        assertEquals("https://work.example.com/gw", GatewayUrl.validate(" https://work.example.com/gw/ "))
        assertEquals("http://100.65.118.116:9119", GatewayUrl.validate("http://100.65.118.116:9119"))
        assertEquals("http://192.168.1.5:9119", GatewayUrl.validate("http://192.168.1.5:9119"))
        for (bad in listOf("http://example.com", "ftp://100.65.1.1", "https://", "not a url", "")) {
            try { GatewayUrl.validate(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
        assertEquals("work.example.com", GatewayUrl.hostName("https://work.example.com/gw"))
    }

    @Test fun okHttpTransportPostsJsonWithBearerAndReturnsStatusAndBody() = runBlocking<Unit> {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"x"}"""))
            s.start()
            val r = OkHttpTransport().post(s.url("/auth/x").toString(), "tok", """{"a":1}""")
            assertEquals(401, r.status)
            assertEquals("""{"error":"x"}""", r.body)
            val req = s.takeRequest()
            assertEquals("Bearer tok", req.getHeader("Authorization"))
            assertEquals("""{"a":1}""", req.body.readUtf8())
        }
    }

    @Test fun logoutWipesLocalTokensEvenWhenTheServerFails() = runBlocking<Unit> {
        val boom = object : HttpTransport {
            override suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply = throw java.io.IOException("down")
        }
        val auth = GatewayAuth("http://100.65.1.1:9119", boom, GatewayTokens("a", "r", 1L, "basic"))
        auth.logout()
        assertNull(auth.tokens)
    }
}
