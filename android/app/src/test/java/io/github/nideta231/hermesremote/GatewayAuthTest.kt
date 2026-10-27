package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.GatewayAuth
import io.github.nideta231.hermesremote.data.GatewayAuthError
import io.github.nideta231.hermesremote.data.GatewayAuthException
import io.github.nideta231.hermesremote.data.GatewayTokens
import io.github.nideta231.hermesremote.data.HttpReply
import io.github.nideta231.hermesremote.data.HttpTransport
import io.github.nideta231.hermesremote.data.Pkce
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeTransport(val reply: (String, String) -> HttpReply) : HttpTransport {
    val calls = mutableListOf<String>()
    val count = AtomicInteger()
    override suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply {
        synchronized(calls) { calls.add(url) }
        count.incrementAndGet()
        delay(50)
        return reply(url, jsonBody)
    }
}

private fun tokensJson(a: String, r: String) =
    """{"access_token":"$a","refresh_token":"$r","token_type":"Bearer","expires_at":1900000000,"provider":"basic","user_id":"u"}"""

class GatewayAuthTest {
    private val base = "http://100.65.118.116:9119"

    @Test fun pkceChallengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
    }

    @Test fun authorizeUrlHasPkceLoopbackStateAndKeepsPrefix() {
        val auth = GatewayAuth("http://host:9119/prefix/", FakeTransport { _, _ -> HttpReply(500, "") })
        val url = auth.authorizeUrl("CHAL", "http://127.0.0.1:5555/cb", "st4te")
        assertTrue(url, url.startsWith("http://host:9119/prefix/auth/native/authorize?"))
        assertTrue(url, url.contains("code_challenge=CHAL"))
        assertTrue(url, url.contains("code_challenge_method=S256"))
        assertTrue(url, url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5555%2Fcb"))
        assertTrue(url, url.contains("state=st4te"))
    }

    @Test fun wrongStateIsRejectedBeforeAnyTokenCall() = runBlocking<Unit> {
        val t = FakeTransport { _, _ -> HttpReply(200, tokensJson("a", "r")) }
        val auth = GatewayAuth(base, t)
        try {
            auth.signIn("code", "verifier", returnedState = "evil", expectedState = "good")
            fail("expected failure")
        } catch (e: GatewayAuthException) {
            assertEquals(GatewayAuthError.FAILED, e.error)
        }
        assertEquals(0, t.count.get())
        val tokens = auth.signIn("code", "verifier", "good", "good")
        assertEquals("a", tokens.accessToken)
        assertEquals(listOf("$base/auth/native/token"), t.calls)
    }

    @Test fun parallelRefreshCallsShareOneHttpRefresh() = runBlocking<Unit> {
        val t = FakeTransport { _, _ -> HttpReply(200, tokensJson("new-a", "new-r")) }
        val auth = GatewayAuth(base, t, GatewayTokens("old-a", "old-r", 1, "basic"))
        val all = (1..5).map { async { auth.refresh() } }.awaitAll()
        assertEquals(1, t.count.get())
        assertEquals(listOf("$base/auth/native/refresh"), t.calls)
        assertTrue(all.all { it.accessToken == "new-a" && it.refreshToken == "new-r" })
    }

    @Test fun refresh503IsRetryLaterAndKeepsTokensWhile401IsSignedOut() = runBlocking<Unit> {
        var status = 503
        val t = FakeTransport { _, _ ->
            if (status == 503) HttpReply(503, "") else HttpReply(401, """{"error":"session_expired"}""")
        }
        val auth = GatewayAuth(base, t, GatewayTokens("old-a", "old-r", 1, "basic"))
        try { auth.refresh(); fail() } catch (e: GatewayAuthException) {
            assertEquals(GatewayAuthError.RETRY_LATER, e.error)
        }
        assertEquals("old-r", auth.tokens?.refreshToken)
        status = 401
        try { auth.refresh(); fail() } catch (e: GatewayAuthException) {
            assertEquals(GatewayAuthError.SIGNED_OUT, e.error)
        }
        assertFalse(auth.tokens != null)
    }

    @Test fun everyWsTicketCallIsAFreshRequest() = runBlocking<Unit> {
        val n = AtomicInteger()
        val t = FakeTransport { _, _ -> HttpReply(200, """{"ticket":"t${n.incrementAndGet()}","ttl_seconds":30}""") }
        val auth = GatewayAuth(base, t, GatewayTokens("a", "r", 1, "basic"))
        assertEquals("t1", auth.wsTicket())
        assertEquals("t2", auth.wsTicket())
        assertEquals(2, t.calls.count { it == "$base/api/auth/ws-ticket" })
    }
}
