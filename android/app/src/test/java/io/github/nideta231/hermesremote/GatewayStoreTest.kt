package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.GatewayCredentialStore
import io.github.nideta231.hermesremote.data.GatewayCredentials
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.ProfileStore
import io.github.nideta231.hermesremote.data.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

internal class MemPrefs : android.content.SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit() = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String?, Any?>()
        private var cleared = false
        override fun putString(k: String?, v: String?) = also { pending[k] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = also { pending[k] = v }
        override fun putInt(k: String?, v: Int) = also { pending[k] = v }
        override fun putLong(k: String?, v: Long) = also { pending[k] = v }
        override fun putFloat(k: String?, v: Float) = also { pending[k] = v }
        override fun putBoolean(k: String?, v: Boolean) = also { pending[k] = v }
        override fun remove(k: String?) = also { pending[k] = null }
        override fun clear() = also { cleared = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() = flush()
        private fun flush() {
            if (cleared) map.clear()
            pending.forEach { (k, v) -> if (k != null) { if (v == null) map.remove(k) else map[k] = v } }
        }
    }
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

/** Reverses the bytes: enough to prove the stored form is not the plain token. */
internal object ReverseCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(blob: ByteArray) = blob.reversedArray()
}

class GatewayStoreTest {
    @Test fun oldProfileListLoadsAsBridge() {
        val prefs = MemPrefs()
        prefs.map["list"] = """[{"id":"a","name":"Desk"},{"id":"b","name":""}]"""
        val items = ProfileStore(prefs).list()
        assertEquals(listOf(HostKind.BRIDGE, HostKind.BRIDGE), items.map { it.kind })
    }

    @Test fun gatewayHostPersistsKindAndRenameRemoveWork() {
        val prefs = MemPrefs()
        val g = ProfileStore(prefs).add("Cloud", HostKind.GATEWAY)
        val reloaded = ProfileStore(prefs)
        assertEquals(HostKind.GATEWAY, reloaded.list().first { it.id == g.id }.kind)
        reloaded.rename(g.id, "Work")
        val renamed = ProfileStore(prefs).list().first { it.id == g.id }
        assertEquals("Work", renamed.name)
        assertEquals(HostKind.GATEWAY, renamed.kind)
        ProfileStore(prefs).remove(g.id)
        assertFalse(ProfileStore(prefs).list().any { it.id == g.id })
    }

    @Test fun tokensAreEncryptedAtRest() {
        val prefs = MemPrefs()
        val store = GatewayCredentialStore(prefs, ReverseCipher)
        val c = GatewayCredentials("https://gw.example", "ACCESS-123", "REFRESH-456", 1234L)
        store.save(c)
        assertFalse(prefs.map.values.any { it.toString().contains("ACCESS-123") || it.toString().contains("REFRESH-456") })
        assertEquals(c, GatewayCredentialStore(prefs, ReverseCipher).load())
    }

    @Test fun removingAHostClearsItsPairingDraftsAndGatewayStore() {
        val files = mutableMapOf<String, MemPrefs>()
        fun prefsOf(name: String) = files.getOrPut(name) { MemPrefs() }
        GatewayCredentialStore(prefsOf(ProfileStore.gatewayFile("g1")), ReverseCipher)
            .save(GatewayCredentials("https://gw.example", "ACCESS-123", "REFRESH-456", 1L))
        prefsOf(ProfileStore.pairingFile("g1")).map["token"] = "t"
        prefsOf(ProfileStore.draftsFile("g1")).map["d"] = "x"
        prefsOf(ProfileStore.gatewayFile("other")).map["url"] = "keep"

        ProfileStore.clearHostFiles("g1", ::prefsOf)

        assertEquals(0, prefsOf(ProfileStore.gatewayFile("g1")).map.size)
        assertEquals(0, prefsOf(ProfileStore.pairingFile("g1")).map.size)
        assertEquals(0, prefsOf(ProfileStore.draftsFile("g1")).map.size)
        assertEquals(1, prefsOf(ProfileStore.gatewayFile("other")).map.size)
        // a bridge host has nothing stored under gateway_<id>: clearing is harmless
        ProfileStore.clearHostFiles("bridge1", ::prefsOf)
    }

    @Test fun corruptedBlobClearsAndReportsSignedOut() {
        val prefs = MemPrefs()
        val store = GatewayCredentialStore(prefs, ReverseCipher)
        store.save(GatewayCredentials("https://gw.example", "a", "r", 1L))
        prefs.map["access"] = "!!not base64!!"
        assertNull(store.load())
        assertEquals(0, prefs.map.size)
    }
}
