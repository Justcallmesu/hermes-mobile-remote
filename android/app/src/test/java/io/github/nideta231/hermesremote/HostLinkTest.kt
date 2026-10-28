package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.BridgeClient.Probe
import io.github.nideta231.hermesremote.data.GatewayAuth
import io.github.nideta231.hermesremote.data.GatewayRestClient
import io.github.nideta231.hermesremote.data.GatewayTokens
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.HostLink
import io.github.nideta231.hermesremote.data.HostLinking
import io.github.nideta231.hermesremote.data.HttpReply
import io.github.nideta231.hermesremote.data.HttpTransport
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.ProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HostLinkTest {

    @Test fun candidateUrlsUseTheGatewayHostOnTheBridgePortAndSkipForbiddenHosts() {
        assertEquals(listOf("http://100.64.1.2:8650"), HostLinking.candidateUrls("https://100.64.1.2:9119"))
        assertEquals(listOf("http://work.tail1234.ts.net:8650"), HostLinking.candidateUrls("https://work.tail1234.ts.net/"))
        assertEquals(listOf("https://192.168.1.5:8650"), HostLinking.candidateUrls("http://192.168.1.5:9119"))
        assertEquals(emptyList<String>(), HostLinking.candidateUrls("https://work.example.com"))
        assertEquals(emptyList<String>(), HostLinking.candidateUrls("not a url"))
    }

    @Test fun aLinkStoresReloadsAndGoesWhenEitherHostIsRemoved() {
        val prefs = MemPrefs()
        val s = ProfileStore(prefs)
        val b = s.add("PC", HostKind.BRIDGE)
        val g = s.add("gw", HostKind.GATEWAY)
        s.link(b.id, g.id)
        assertEquals(listOf(HostLink(b.id, g.id)), ProfileStore(prefs).links())
        assertEquals(g.id, ProfileStore(prefs).linkedWith(b.id))
        assertEquals(b.id, ProfileStore(prefs).linkedWith(g.id))
        s.unlink(g.id)
        assertEquals(emptyList<HostLink>(), s.links())
        s.link(b.id, g.id); s.remove(g.id)
        assertEquals(emptyList<HostLink>(), s.links())
        val g2 = s.add("gw2", HostKind.GATEWAY)
        s.link(b.id, g2.id); s.remove(b.id)
        assertNull(s.linkedWith(g2.id))
    }

    @Test fun unreachableOffersNothingAndOkOrUnauthorizedOffersPairing() = runBlocking {
        val asked = mutableListOf<String>()
        fun find(p: Probe) = runBlocking { HostLinking.findBridgeFor("https://100.64.1.2:9119") { asked += it; p } }
        assertNull(find(Probe.UNREACHABLE))
        assertEquals("http://100.64.1.2:8650", find(Probe.OK))
        assertEquals("http://100.64.1.2:8650", find(Probe.UNAUTHORIZED))
        assertEquals("http://100.64.1.2:8650", asked.first())
        assertNull(HostLinking.findBridgeFor("https://work.example.com") { error("must not probe a forbidden host") })
    }

    @Test fun aGatewayMatchesABridgeOnlyWhenItsHostIsOneOfTheBridgesAddresses() {
        val bridgeAddrs = mapOf("b1" to setOf("192.168.1.5", "100.64.1.2"), "b2" to setOf("10.0.0.9"))
        val gateways = mapOf("g1" to "https://100.64.1.2:9119", "g2" to "https://work.example.com")
        val prefs = MemPrefs().also { it.map["pairing_b1"] = "token-a"; it.map["drafts_b1"] = "draft"; it.map["pairing_g1"] = "token-g" }
        val before = prefs.map.toMap()
        assertEquals(listOf(HostLink("b1", "g1")), HostLinking.matches(bridgeAddrs, gateways))
        assertEquals(emptyList<HostLink>(), HostLinking.matches(mapOf("b2" to setOf("10.0.0.9")), gateways))
        val s = ProfileStore(MemPrefs())
        HostLinking.matches(bridgeAddrs, gateways).forEach { s.link(it.bridgeId, it.gatewayId) }
        assertEquals(before, prefs.map) // linking writes only the link record, never a host's own files
    }

    @Test fun neitherCredentialGoesToTheOtherServer() = runBlocking {
        // The probe for a bridge is tokenless.
        assertNull(HostLinking.probeRequest("http://100.64.1.2:8650").header("Authorization"))
        // The bridge's token is refused for a gateway host (not tailnet, not pinned LAN).
        try {
            BridgeClient(Pairing("https://work.example.com:8650", "d", "hrb_" + "x".repeat(24))).me()
            fail("bridge token must not leave for a gateway host")
        } catch (e: IOException) { assertTrue(e.message!!.contains("Refusing")) }
        // The gateway's token only goes to the gateway's own base URL.
        val urls = mutableListOf<String>()
        val http = object : HttpTransport {
            override suspend fun post(url: String, bearer: String?, jsonBody: String) = HttpReply(200, "{}")
            override suspend fun send(method: String, url: String, bearer: String?, jsonBody: String?): HttpReply {
                urls += url; return HttpReply(200, """{"profiles":[]}""")
            }
        }
        val auth = GatewayAuth("https://100.64.1.2:9119", http, GatewayTokens("gw-access", "r", 0L, "p"))
        GatewayRestClient("https://100.64.1.2:9119", auth, http).profiles()
        assertTrue(urls.all { it.startsWith("https://100.64.1.2:9119/") && !it.contains(":8650") })
        assertFalse(urls.isEmpty())
    }
}
