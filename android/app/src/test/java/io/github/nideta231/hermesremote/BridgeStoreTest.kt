package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeStart
import io.github.nideta231.hermesremote.data.HostLink
import io.github.nideta231.hermesremote.data.ProfileStore
import io.github.nideta231.hermesremote.data.bridgeStartState
import io.github.nideta231.hermesremote.data.bridgeStoreFor
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeStoreTest {
    private val link = HostLink("b1", "g1")

    @Test fun `linked bridge store is the bridge file from either entry, unlinked keeps the active file`() {
        assertEquals(ProfileStore.pairingFile("b1"), bridgeStoreFor(link, "b1"))
        assertEquals(ProfileStore.pairingFile("b1"), bridgeStoreFor(link, "g1"))
        assertEquals(ProfileStore.pairingFile("b2"), bridgeStoreFor(null, "b2"))
    }

    @Test fun `start takes its values from the bridge store when opened from the gateway entry`() {
        val stores = mapOf(
            ProfileStore.pairingFile("b1") to BridgeStart("bridge-chat", mapOf("lan" to listOf("10.0.0.2")), "lan", setOf("monitor")),
            ProfileStore.pairingFile("g1") to BridgeStart("gateway-chat", mapOf("tailnet" to listOf("100.1.1.1")), "tailnet", setOf()),
        )
        val got = bridgeStartState(link, "g1") { stores.getValue(it) }
        assertEquals(stores.getValue(ProfileStore.pairingFile("b1")), got)
    }
}
