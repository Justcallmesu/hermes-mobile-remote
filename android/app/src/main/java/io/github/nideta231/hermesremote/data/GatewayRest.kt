package io.github.nideta231.hermesremote.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** What the drawer, profile switcher, pin/rename, status page and chat pictures need from the active host. */
interface SessionsSource {
    suspend fun sessions(limit: Int = 50, offset: Int = 0, profile: String? = null): Pair<List<SessionSummary>, Boolean>
    suspend fun profiles(): List<HermesProfile>
    suspend fun setPinned(id: String, pinned: Boolean, profile: String? = null): Any?
    suspend fun renameSession(id: String, title: String, profile: String? = null): Any?
    suspend fun status(): List<ComponentStatus>
    /** Picture bytes, or null for a placeholder (gateway hosts: refused or too big). */
    suspend fun media(path: String): ByteArray?
}

/** What the Scheduled page needs from the active host: the profile's cron jobs, their runs, create, pause / resume / run now. */
interface CronSource {
    suspend fun cronJobs(profile: String? = null): List<CronJob>
    suspend fun cronRuns(jobId: String, profile: String? = null, limit: Int = 10): List<CronRun>
    suspend fun createCronJob(name: String, schedule: String, prompt: String, profile: String? = null): CronJob?
    /** [action] is pause, resume or trigger (run now). Returns the job as it is afterwards, when the host says. */
    suspend fun cronAction(jobId: String, action: String, profile: String? = null): CronJob?
}

/** Things only a bridge serves; a gateway host hides the screens and controls behind them. */
enum class HostFeature { PROJECT_IDEA, CHAT_FILE_DOWNLOAD, BRIDGE_STATUS }

/** One URL path segment, percent-encoded (spaces as %20); shared by [BridgeClient] and [GatewayRestClient]. */
internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

fun hiddenFeatures(kind: HostKind): Set<HostFeature> =
    if (kind == HostKind.GATEWAY) HostFeature.entries.toSet() else emptySet()

/** Status of a gateway from its public `GET /api/status` (W1). Only gateway fields: no bridge, tailscale or desktop rows. */
fun parseGatewayStatus(o: JSONObject): List<ComponentStatus> {
    val running = o.optBoolean("gateway_running")
    val version = o.str("version")?.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "?"
    return listOf(ComponentStatus("gateway", "Hermes gateway", running, if (running) version else "$version, not running", null))
}

/**
 * The gateway's own REST routes with the bearer token (W7, W10-W13). A bearer 401 `session_expired` triggers one
 * [GatewayAuth.refresh] and a retry; if that fails too, [onSignedOut] runs and the sign-in error is thrown.
 */
class GatewayRestClient(
    baseUrl: String,
    private val auth: GatewayAuth,
    private val http: HttpTransport,
    private val onSignedOut: () -> Unit = {},
) : SessionsSource, CronSource {
    private val base = baseUrl.trimEnd('/')

    private fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val b = (base + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private fun isSessionExpired(r: HttpReply) = r.status == 401 &&
        runCatching { JSONObject(r.body).optString("error") }.getOrNull() == "session_expired"

    /** The reply of [method] [path]; non-2xx other than the refresh case throws [BridgeException] (as the bridge's client does). */
    private suspend fun raw(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null): HttpReply {
        suspend fun once() = http.send(method, url(path, query), auth.tokens?.accessToken, body?.toString())
        var r = once()
        if (isSessionExpired(r)) {
            try {
                auth.refresh()
            } catch (e: GatewayAuthException) {
                if (e.error == GatewayAuthError.SIGNED_OUT) onSignedOut()
                throw e
            }
            r = once()
            if (isSessionExpired(r)) {
                onSignedOut()
                throw GatewayAuthException(GatewayAuthError.SIGNED_OUT, "session expired")
            }
        }
        return r
    }

    private suspend fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null): JSONObject {
        val r = raw(method, path, query, body)
        if (r.status !in 200..299) {
            val msg = runCatching { JSONObject(r.body).optString("detail").ifEmpty { null } }.getOrNull() ?: "HTTP ${r.status}"
            throw BridgeException(r.status, "http_${r.status}", msg)
        }
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    override suspend fun sessions(limit: Int, offset: Int, profile: String?): Pair<List<SessionSummary>, Boolean> {
        val o = call("GET", "/api/sessions", mapOf("limit" to limit, "offset" to offset, "profile" to profileParam(profile)))
        val list = o.optJSONArray("sessions").objects().map(::parseSession)
        val total = if (o.isNull("total")) null else o.optInt("total")
        return list to (if (total != null) offset + list.size < total else list.size >= limit)
    }

    override suspend fun profiles(): List<HermesProfile> = parseProfiles(call("GET", "/api/profiles"))

    override suspend fun status(): List<ComponentStatus> = parseGatewayStatus(call("GET", "/api/status"))

    /** Hermes' own host stats (CPU, RAM, disk, load, uptime): the PC page of a gateway host. */
    suspend fun systemStats(): PcNow = parseHermesSystemStats(call("GET", "/api/system/stats"))

    override suspend fun setPinned(id: String, pinned: Boolean, profile: String?): Any? = patch(id, JSONObject().put("pinned", pinned), profile)

    override suspend fun renameSession(id: String, title: String, profile: String?): Any? = patch(id, JSONObject().put("title", title), profile)

    private suspend fun patch(id: String, body: JSONObject, profile: String?) =
        call("PATCH", "/api/sessions/${enc(id)}", body = body.apply { profileParam(profile)?.let { put("profile", it) } })

    // Cron: the server's profile defaults to all, so a profile is always sent.
    private fun cronProfile(profile: String?) = mapOf("profile" to (profile?.takeIf { it.isNotBlank() } ?: DEFAULT_HERMES_PROFILE))

    /** The reply is a bare array or an object holding it under [key]; the parsers want the object. */
    private suspend fun callWrapped(key: String, method: String, path: String, query: Map<String, Any?>): JSONObject {
        val r = raw(method, path, query)
        if (r.status !in 200..299) throw BridgeException(r.status, "http_${r.status}", "HTTP ${r.status}")
        val t = r.body.trim()
        return if (t.startsWith("[")) JSONObject().put(key, org.json.JSONArray(t)) else JSONObject(t.ifEmpty { "{}" })
    }

    override suspend fun cronJobs(profile: String?): List<CronJob> =
        parseCronJobs(callWrapped("jobs", "GET", "/api/cron/jobs", cronProfile(profile)))

    override suspend fun cronRuns(jobId: String, profile: String?, limit: Int): List<CronRun> =
        parseCronRuns(callWrapped("runs", "GET", "/api/cron/jobs/${enc(jobId)}/runs", cronProfile(profile) + ("limit" to limit)))

    override suspend fun createCronJob(name: String, schedule: String, prompt: String, profile: String?): CronJob? =
        asJob(call("POST", "/api/cron/jobs", cronProfile(profile), JSONObject().put("name", name).put("schedule", schedule)
            .put("prompt", prompt).put("deliver", "local").put("paused", false)))

    override suspend fun cronAction(jobId: String, action: String, profile: String?): CronJob? =
        asJob(call("POST", "/api/cron/jobs/${enc(jobId)}/$action", cronProfile(profile)))

    /** The job itself or `{"job": {...}}`; null when the reply is neither. */
    private fun asJob(o: JSONObject): CronJob? = parseCronJob(o.optJSONObject("job") ?: o)

    override suspend fun media(path: String): ByteArray? {
        val r = raw("GET", "/api/media", mapOf("path" to path))
        return when {
            r.status in listOf(403, 404, 413, 415) -> null
            r.status !in 200..299 -> throw BridgeException(r.status, "http_${r.status}", "HTTP ${r.status}")
            else -> {
                val data = JSONObject(r.body).getString("data_url")
                Base64.getDecoder().decode(data.substringAfter("base64,"))
            }
        }
    }
}
