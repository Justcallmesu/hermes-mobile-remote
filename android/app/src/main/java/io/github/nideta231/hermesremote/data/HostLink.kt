package io.github.nideta231.hermesremote.data

import io.github.nideta231.hermesremote.data.BridgeClient.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A bridge host and a gateway host the user said are one PC. Each keeps its own token, drafts and chat. */
data class HostLink(val bridgeId: String, val gatewayId: String)

/** Finding out that a gateway host and a bridge host are the same PC. Pure apart from the probe the caller passes. */
object HostLinking {
    const val BRIDGE_PORT = 8650

    /**
     * Where this gateway's PC would answer as a bridge: the gateway URL's host on [BRIDGE_PORT], LAN over https and
     * Tailscale over http. A host the token rules forbid (a public name) yields nothing: no bridge is ever looked for there.
     */
    fun candidateUrls(gatewayUrl: String): List<String> {
        val host = EndpointResolver.hostOf(gatewayUrl) ?: return emptyList()
        val transport = EndpointResolver.transportOf(host)
        val https = transport == Transport.LAN
        if (!Transport.allowsToken(host, https, pinned = true)) return emptyList()
        return listOf(Endpoint(transport, host, BRIDGE_PORT).url)
    }

    /** The URL of a bridge answering on the gateway's host (OK or UNAUTHORIZED), or null. */
    suspend fun findBridgeFor(gatewayUrl: String, probe: suspend (String) -> Probe): String? =
        candidateUrls(gatewayUrl).firstOrNull { probe(it) != Probe.UNREACHABLE }

    /** Every pair where the gateway's host is one of the bridge's addresses. Keys are host ids. */
    fun matches(bridgeAddresses: Map<String, Set<String>>, gatewayUrls: Map<String, String>): List<HostLink> =
        bridgeAddresses.flatMap { (b, addrs) ->
            gatewayUrls.mapNotNull { (g, url) ->
                HostLink(b, g).takeIf { EndpointResolver.hostOf(url)?.lowercase() in addrs.map { it.lowercase() } }
            }
        }

    /** The link a host belongs to, found from its bridge id or its gateway id alike. */
    fun linkOf(links: List<HostLink>, hostId: String): HostLink? =
        links.firstOrNull { it.bridgeId == hostId || it.gatewayId == hostId }

    /** The probe request: no credential of either server, only a look at whether a bridge answers. */
    fun probeRequest(bridgeUrl: String): Request = Request.Builder().url("${bridgeUrl.trimEnd('/')}/v1/me").build()

    /**
     * The real probe, only for unpaired candidate hosts found after a gateway sign-in (a paired bridge is probed with
     * `BridgeClient.probe`, which pins its certificate). It carries no token, so a LAN bridge's self-signed certificate is accepted here (nothing secret is
     * sent); pairing it later pins the certificate from the QR code.
     */
    suspend fun probe(bridgeUrl: String): Probe = runInterruptible(Dispatchers.IO) {
        runCatching {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            SharedHttp.client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
                .newCall(probeRequest(bridgeUrl)).execute()
                .use { when { it.isSuccessful -> Probe.OK; it.code == 401 -> Probe.UNAUTHORIZED; else -> Probe.UNREACHABLE } }
        }.getOrDefault(Probe.UNREACHABLE)
    }
}
