package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The bridge's small REST surface (identity, status, profiles, the sidebar list, approval mode) plus the
 * factory for the live [Gateway] socket. Everything conversational goes over the socket.
 */
class BridgeClient(
    private val pairing: Pairing,
    baseClient: OkHttpClient = OkHttpClient(),
    // Tests only: lets a loopback TLS server stand in for the PC. Production always uses the
    // real rule (tailnet, or LAN over pinned HTTPS).
    private val hostGuard: (host: String, https: Boolean) -> Boolean =
        { host, https -> Transport.allowsToken(host, https, pairing.pin != null) },
) {
    private val base = pairing.url.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Hermes replies to the bridge's own pings; this one keeps NATs and the phone's radio from
        // silently dropping an idle socket, and notices a dead link within ~40 s.
        .pingInterval(20, TimeUnit.SECONDS)
        .apply {
            // LAN: HTTPS pinned to the bridge's own certificate (hostname is irrelevant; the pin
            // is the identity). Tailscale: plain HTTP inside WireGuard.
            pairing.pin?.let { pin ->
                val tm = CertPin.trustManager(pin)
                sslSocketFactory(CertPin.socketFactory(tm), tm)
                hostnameVerifier { _, _ -> true }
            }
        }
        .addInterceptor { chain ->
            val req = chain.request()
            // Defense in depth: the token only ever goes to a tailnet host, or to a LAN host over
            // pinned TLS. Never in clear text across a Wi-Fi.
            if (!hostGuard(req.url.host, req.url.isHttps))
                throw IOException("Refusing to send credentials to ${req.url.host} without a verified connection")
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${pairing.token}").build())
        }
        .build()

    /** The live connection. ws(s) follows the pairing's scheme; the token rides in the header. */
    fun gateway(): Gateway {
        val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/v1/ws"
        return Gateway(http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(), wsUrl)
    }

    private fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val b = (base + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private fun parseError(resp: Response, body: String): BridgeException {
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val code = err?.str("code") ?: "http_${resp.code}"
        val message = err?.str("message") ?: "HTTP ${resp.code}"
        return BridgeException(resp.code, code, message)
    }

    private suspend fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(),
                             body: JSONObject? = null): JSONObject = runInterruptible(Dispatchers.IO) {
        val reqBody = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "POST" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        val req = Request.Builder().url(url(path, query)).method(method, reqBody).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseError(resp, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    // ------------------------------------------------------------ endpoints

    suspend fun me() = call("GET", "/v1/me")

    /** An image a chat shows, by the PC path Hermes stored for it (`/v1/media`; bridges before 1.11 answer 404). */
    suspend fun media(path: String): ByteArray = runInterruptible(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url("/v1/media", mapOf("path" to path))).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw parseError(resp, resp.body?.string().orEmpty())
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Cheap reachability check with a short timeout: an absent LAN must not stall switching. */
    suspend fun reachable(): Boolean = runInterruptible(Dispatchers.IO) {
        runCatching {
            http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$base/v1/me").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun status(): List<ComponentStatus> = StatusMapper.map(call("GET", "/v1/status").getJSONObject("components"))

    /** Hermes profiles the phone may use. Older bridges have no such route (404): treat as default only. */
    suspend fun profiles(): List<HermesProfile> = parseProfiles(call("GET", "/v1/profiles"))

    // Every call below acts on one Hermes profile: [profile] null (or "default") is the default profile.

    /** The desktop sidebar's list: same query, same order (newest activity first). */
    suspend fun sessions(limit: Int = 50, offset: Int = 0, profile: String? = null): Pair<List<SessionSummary>, Boolean> {
        val o = call("GET", "/v1/sessions", mapOf("limit" to limit, "offset" to offset) + scoped(profile))
        val list = o.optJSONArray("sessions").objects().map(::parseSession)
        val total = if (o.isNull("total")) null else o.optInt("total")
        return list to (if (total != null) offset + list.size < total else list.size >= limit)
    }

    suspend fun renameSession(id: String, title: String, profile: String? = null) =
        call("PATCH", "/v1/sessions/${enc(id)}", scoped(profile), JSONObject().put("title", title))

    suspend fun setPinned(id: String, pinned: Boolean, profile: String? = null) =
        call("PATCH", "/v1/sessions/${enc(id)}", scoped(profile), JSONObject().put("pinned", pinned))

    suspend fun approvalMode(profile: String? = null): String =
        call("GET", "/v1/settings/approvals", scoped(profile)).getString("mode")

    suspend fun setApprovalMode(mode: String, profile: String? = null): String =
        call("PUT", "/v1/settings/approvals", scoped(profile), JSONObject().put("mode", mode)).getString("mode")

    /** Hermes' profile default model (new chats); null on bridges older than this route. */
    suspend fun defaultModel(profile: String? = null): Pair<String, String> =
        call("GET", "/v1/settings/model", scoped(profile)).let { it.optString("model") to it.optString("provider") }

    /** Returns the bridge's reply: either the applied model or `confirm_required` + `confirm_message`. */
    suspend fun setDefaultModel(model: String, provider: String, confirmed: Boolean, profile: String? = null): JSONObject =
        call("PUT", "/v1/settings/model", scoped(profile), JSONObject().put("model", model).put("provider", provider)
            .put("confirm_expensive_model", confirmed))

    /** The profile's scheduled jobs. Bridges older than this route answer 404. */
    suspend fun cronJobs(profile: String? = null): List<CronJob> = parseCronJobs(call("GET", "/v1/cron/jobs", scoped(profile)))

    suspend fun cronRuns(jobId: String, profile: String? = null, limit: Int = 10): List<CronRun> =
        parseCronRuns(call("GET", "/v1/cron/jobs/${enc(jobId)}/runs", mapOf("limit" to limit) + scoped(profile)))

    /** A new job: [prompt] run on [schedule] ("every 30m", "every day 8am", "0 9 * * 1-5"), saved locally. */
    suspend fun createCronJob(name: String, schedule: String, prompt: String, profile: String? = null): CronJob? =
        call("POST", "/v1/cron/jobs", scoped(profile), JSONObject().put("name", name).put("schedule", schedule)
            .put("prompt", prompt)).optJSONObject("job")?.let(::parseCronJob)

    /** [action] is pause, resume or trigger (run now). Returns the job as it is afterwards, when Hermes says. */
    suspend fun cronAction(jobId: String, action: String, profile: String? = null): CronJob? =
        call("POST", "/v1/cron/jobs/${enc(jobId)}/$action", scoped(profile)).optJSONObject("job")?.let(::parseCronJob)

    private fun scoped(profile: String?): Map<String, Any?> = mapOf("profile" to profileParam(profile))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object StatusMapper {
    fun map(c: JSONObject): List<ComponentStatus> {
        val out = mutableListOf<ComponentStatus>()
        c.optJSONObject("bridge")?.let {
            out += ComponentStatus("bridge", "Bridge", true, "v${it.str("version")}", null)
        }
        c.optJSONObject("hermes")?.let {
            val ok = it.str("status") == "ok"
            out += ComponentStatus("hermes", "Hermes agent", ok,
                if (ok) "v${it.str("version") ?: "?"}" else (it.str("error") ?: "not running"),
                when {
                    ok && it.optBoolean("shared_with_desktop") -> "Same Hermes as the desktop app"
                    ok -> "Started by the bridge"
                    it.optBoolean("starts_on_connect") -> "Starts when the app connects"
                    else -> null
                })
        }
        c.optJSONObject("tailscale")?.let {
            out += ComponentStatus("tailscale", "Tailscale (PC)", it.str("status") == "ok",
                it.str("dns_name")?.takeIf { d -> d.isNotEmpty() } ?: (it.str("host") ?: it.str("status") ?: "?"), null)
        }
        return out
    }
}
