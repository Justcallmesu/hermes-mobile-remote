package io.github.nideta231.hermesremote.data

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** Minimal HTTP seam: the app wires OkHttp behind it, tests fake it. */
interface HttpTransport {
    suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply
}

class HttpReply(val status: Int, val body: String)

enum class GatewayAuthError { SIGNED_OUT, RETRY_LATER, FAILED }

class GatewayAuthException(val error: GatewayAuthError, message: String) : Exception(message)

class GatewayTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val provider: String,
)

object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String =
        b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** Native (RFC 8252) sign-in against a gateway. [baseUrl] may carry a path prefix. */
class GatewayAuth(
    baseUrl: String,
    private val http: HttpTransport,
    tokens: GatewayTokens? = null,
    /** Called with every refreshed token set, so the caller can persist the rotated refresh token. */
    private val onRefreshed: (GatewayTokens) -> Unit = {},
) {
    private val base = baseUrl.trimEnd('/')
    private val lock = Any()
    private var inFlight: CompletableDeferred<GatewayTokens>? = null

    @Volatile var tokens: GatewayTokens? = tokens
        private set

    fun authorizeUrl(challenge: String, redirectUri: String, state: String, provider: String? = null): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = StringBuilder("code_challenge=${e(challenge)}&code_challenge_method=S256")
            .append("&redirect_uri=${e(redirectUri)}&state=${e(state)}")
        if (provider != null) q.append("&provider=${e(provider)}")
        return "$base/auth/native/authorize?$q"
    }

    suspend fun signIn(code: String, verifier: String, returnedState: String, expectedState: String): GatewayTokens {
        if (returnedState != expectedState) fail(GatewayAuthError.FAILED, "state mismatch")
        val r = http.post(
            "$base/auth/native/token", null,
            JSONObject().put("code", code).put("code_verifier", verifier).toString(),
        )
        if (r.status != 200) fail(GatewayAuthError.FAILED, "token HTTP ${r.status}")
        return parse(r.body, null).also { tokens = it }
    }

    /** One refresh at a time: parallel callers share the in-flight result. */
    suspend fun refresh(): GatewayTokens {
        val mine = CompletableDeferred<GatewayTokens>()
        val existing = synchronized(lock) { inFlight ?: run { inFlight = mine; null } }
        if (existing != null) return existing.await()
        try {
            mine.complete(doRefresh())
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
        } finally {
            synchronized(lock) { inFlight = null }
        }
        return mine.await()
    }

    private suspend fun doRefresh(): GatewayTokens {
        val cur = tokens ?: fail(GatewayAuthError.SIGNED_OUT, "not signed in")
        val r = http.post(
            "$base/auth/native/refresh", null,
            JSONObject().put("refresh_token", cur.refreshToken).put("provider", cur.provider).toString(),
        )
        when {
            r.status == 200 -> return parse(r.body, cur.provider).also { tokens = it; onRefreshed(it) }
            r.status == 401 && errorCode(r.body) == "session_expired" -> {
                tokens = null
                fail(GatewayAuthError.SIGNED_OUT, "session expired")
            }
            r.status == 503 -> fail(GatewayAuthError.RETRY_LATER, "gateway unavailable")
            else -> fail(GatewayAuthError.FAILED, "refresh HTTP ${r.status}")
        }
    }

    /** A new single-use ticket on every call; never cached. */
    suspend fun wsTicket(): String {
        val cur = tokens ?: fail(GatewayAuthError.SIGNED_OUT, "not signed in")
        val r = http.post("$base/api/auth/ws-ticket", cur.accessToken, "{}")
        when {
            r.status == 200 -> return runCatching { JSONObject(r.body).getString("ticket") }
                .getOrElse { fail(GatewayAuthError.FAILED, "bad ticket reply") }
            r.status == 401 && errorCode(r.body) == "session_expired" -> fail(GatewayAuthError.SIGNED_OUT, "session expired")
            r.status == 503 -> fail(GatewayAuthError.RETRY_LATER, "gateway unavailable")
            else -> fail(GatewayAuthError.FAILED, "ticket HTTP ${r.status}")
        }
    }

    /** Best effort server logout; local tokens are always wiped. */
    suspend fun logout() {
        val cur = tokens
        tokens = null
        if (cur != null) runCatching { http.post("$base/auth/logout", cur.accessToken, "{}") }
    }

    private fun errorCode(body: String): String? =
        runCatching { JSONObject(body).optString("error").ifEmpty { null } }.getOrNull()

    private fun parse(body: String, fallbackProvider: String?): GatewayTokens = try {
        val j = JSONObject(body)
        GatewayTokens(
            j.getString("access_token"), j.getString("refresh_token"),
            j.optLong("expires_at"), j.optString("provider").ifEmpty { fallbackProvider ?: "" },
        )
    } catch (e: Exception) {
        fail(GatewayAuthError.FAILED, "bad token reply")
    }

    private fun fail(error: GatewayAuthError, msg: String): Nothing = throw GatewayAuthException(error, msg)
}
