package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts a secret to an opaque blob; injected so tests can fake the Keystore. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** The Keystore AES-GCM scheme (iv-prefixed blob), shared by [CredentialStore] and the gateway store; one key per [alias]. */
class KeystoreTokenCipher(private val alias: String) : TokenCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** What a signed-in gateway host keeps: [expiresAt] is epoch millis of the access token. */
data class GatewayCredentials(val baseUrl: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

/**
 * Credentials of one gateway host, in its own prefs file ([ProfileStore.gatewayFile]). Tokens are
 * encrypted; the file is excluded from backup by the sharedpref rules.
 */
class GatewayCredentialStore(private val prefs: SharedPreferences, private val cipher: TokenCipher) {

    constructor(context: Context, hostId: String) : this(
        context.getSharedPreferences(ProfileStore.gatewayFile(hostId), Context.MODE_PRIVATE), KeystoreTokenCipher("hermes_remote_gateway_key"))

    /** Null when signed out, including when the stored blob cannot be decrypted (store is then cleared). */
    fun load(): GatewayCredentials? {
        val url = prefs.getString("url", null) ?: return null
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return try {
            GatewayCredentials(url, open(access), open(refresh), prefs.getLong("expires_at", 0L))
        } catch (e: Exception) {
            clear()
            null
        }
    }

    fun save(c: GatewayCredentials) {
        prefs.edit()
            .putString("url", c.baseUrl)
            .putString("access", seal(c.accessToken))
            .putString("refresh", seal(c.refreshToken))
            .putLong("expires_at", c.expiresAt)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun seal(s: String) = Base64.getEncoder().encodeToString(cipher.encrypt(s.toByteArray(Charsets.UTF_8)))
    private fun open(s: String) = String(cipher.decrypt(Base64.getDecoder().decode(s)), Charsets.UTF_8)
}
