package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.GatewayAuth
import io.github.nideta231.hermesremote.data.GatewayAuthError
import io.github.nideta231.hermesremote.data.GatewayAuthException
import io.github.nideta231.hermesremote.data.GatewayRestClient
import io.github.nideta231.hermesremote.data.GatewayTokens
import io.github.nideta231.hermesremote.data.HostFeature
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.HttpReply
import io.github.nideta231.hermesremote.data.HttpTransport
import io.github.nideta231.hermesremote.data.hiddenFeatures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Scripted gateway: [reply] sees (method, url, bearer, body); posts (token refresh) go through the same script. */
private class RestFake(val reply: (String, String, String?, String?) -> HttpReply) : HttpTransport {
    val calls = mutableListOf<String>()
    override suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply = send("POST", url, bearer, jsonBody)
    override suspend fun send(method: String, url: String, bearer: String?, jsonBody: String?): HttpReply {
        calls.add("$method $url bearer=$bearer")
        return reply(method, url, bearer, jsonBody)
    }
}

private const val BASE = "http://100.65.118.116:9119"
private const val SESSIONS = """{"sessions":[
  {"id":"20260101_a","title":"First","source":"cli","model":"m1","started_at":1767225600.5,"last_active":1767229200.0,"message_count":4,"pinned":true,"archived":false,"is_active":false,"profile":"default","is_default_profile":true},
  {"id":"20260101_b","title":null,"source":"tui","model":null,"started_at":1767225700.0,"last_active":1767225800.0,"message_count":0,"pinned":false,"archived":false}
 ],"total":3,"limit":2,"offset":0,"storage":"sqlite"}"""
private const val PROFILES = """{"profiles":[{"name":"default","display_name":"Default","description":"","model":"m1","provider":"p","is_default":true},{"name":"work","display_name":"Work","description":"d","model":"m2","provider":"p","is_default":false}]}"""
private const val STATUS = """{"version":"0.22.2","gateway_running":true,"auth_required":true,"auth_providers":["basic"],"auth_flows":["cookie","native_pkce"],"profiles":["default"]}"""

private fun client(t: HttpTransport, signedOut: () -> Unit = {}, access: String = "tok"): GatewayRestClient {
    val auth = GatewayAuth(BASE, t, GatewayTokens(access, "r1", 1900000000, "basic"))
    return GatewayRestClient(BASE, auth, t, signedOut)
}

class GatewayRestTest {
    @Test fun sessionsProfilesAndStatusParseTheVerifiedGatewayShapes() = runBlocking<Unit> {
        val t = RestFake { _, url, _, _ ->
            when {
                url.contains("/api/sessions") -> HttpReply(200, SESSIONS)
                url.endsWith("/api/profiles") -> HttpReply(200, PROFILES)
                url.endsWith("/api/status") -> HttpReply(200, STATUS)
                else -> HttpReply(404, "")
            }
        }
        val c = client(t)
        val (list, more) = c.sessions(2, 0, null)
        assertEquals(listOf("20260101_a", "20260101_b"), list.map { it.id })
        assertTrue(list[0].pinned)
        assertFalse(list[1].pinned)
        assertEquals("First", list[0].displayTitle)
        assertTrue("3 total, 2 shown", more)
        assertTrue(t.calls[0], t.calls[0].contains("limit=2") && t.calls[0].contains("offset=0") && t.calls[0].endsWith("bearer=tok"))
        assertEquals(listOf("default", "work"), c.profiles().map { it.name })
        val st = c.status().single()
        assertEquals("v0.22.2", st.summary)
        assertTrue(st.ok)
    }

    @Test fun pinAndRenameSendPatchWithProfile() = runBlocking<Unit> {
        val seen = mutableListOf<Pair<String, org.json.JSONObject>>()
        val t = RestFake { m, url, _, body -> seen.add("$m ${url.removePrefix(BASE)}" to org.json.JSONObject(body!!)); HttpReply(200, "{}") }
        val c = client(t)
        c.setPinned("s 1", true, "work")
        c.renameSession("s 1", "New", null)
        assertEquals("PATCH /api/sessions/s%201", seen[0].first)
        assertEquals(true, seen[0].second.getBoolean("pinned"))
        assertEquals("work", seen[0].second.getString("profile"))
        assertEquals("New", seen[1].second.getString("title"))
        assertFalse(seen[1].second.has("profile"))
    }

    @Test fun gatewayHidesBridgeOnlyFeaturesAndBridgeHidesNone() {
        assertEquals(
            setOf(HostFeature.VAULT, HostFeature.SCHEDULED, HostFeature.PROJECT_IDEA,
                HostFeature.CHAT_FILE_DOWNLOAD, HostFeature.BRIDGE_STATUS),
            hiddenFeatures(HostKind.GATEWAY),
        )
        assertTrue(hiddenFeatures(HostKind.BRIDGE).isEmpty())
    }

    @Test fun mediaDecodesTheDataUrlAndFallsBackOnRefusals() = runBlocking<Unit> {
        val ok = RestFake { _, url, _, _ ->
            assertTrue(url, url.contains("/api/media?path=%2Ftmp%2Fa+b.png") || url.contains("path=%2Ftmp%2Fa%20b.png"))
            HttpReply(200, """{"data_url":"data:image/png;base64,aGVsbG8="}""")
        }
        assertEquals("hello", String(client(ok).media("/tmp/a b.png")!!))
        for (code in listOf(403, 404, 413, 415)) {
            val refused = RestFake { _, _, _, _ -> HttpReply(code, """{"detail":"no"}""") }
            assertNull("HTTP $code", client(refused).media("/tmp/a.png"))
        }
    }

    @Test fun sessionExpiredRefreshesOnceThenRetriesWithTheNewToken() = runBlocking<Unit> {
        val t = RestFake { _, url, bearer, _ ->
            when {
                url.endsWith("/auth/native/refresh") ->
                    HttpReply(200, """{"access_token":"new","refresh_token":"r2","expires_at":1900000000,"provider":"basic"}""")
                bearer == "old" -> HttpReply(401, """{"error":"session_expired","reason":"invalid_or_expired_session"}""")
                else -> HttpReply(200, PROFILES)
            }
        }
        assertEquals(2, client(t, access = "old").profiles().size)
        assertEquals(3, t.calls.size)
        assertTrue(t.calls[2], t.calls[2].endsWith("bearer=new"))
    }

    @Test fun unauthenticatedIsNotARefreshCase() = runBlocking<Unit> {
        val t = RestFake { _, _, _, _ -> HttpReply(401, """{"error":"unauthenticated","reason":"no_cookie","login_url":"/login"}""") }
        var signedOut = false
        try { client(t, { signedOut = true }).profiles(); fail("expected failure") } catch (e: Exception) {
            assertFalse(e is GatewayAuthException)
        }
        assertEquals(1, t.calls.size)
        assertFalse(signedOut)
    }

    @Test fun refreshRejectedAsksToSignInAgain() = runBlocking<Unit> {
        val t = RestFake { _, _, _, _ -> HttpReply(401, """{"error":"session_expired"}""") }
        var signedOut = false
        try { client(t, { signedOut = true }).sessions(); fail("expected sign-out") } catch (e: GatewayAuthException) {
            assertEquals(GatewayAuthError.SIGNED_OUT, e.error)
        }
        assertTrue(signedOut)
    }
}
