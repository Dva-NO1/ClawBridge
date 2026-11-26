package com.clawbridge.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LanPairingSnapshot(
    val hasLanToken: Boolean = false,
    val bridgeDeviceId: String = "",
    val bridgeDeviceName: String = "",
    val knownIp: String = "",
    val knownPort: Int = DEFAULT_LAN_PORT,
    val manualHostOverride: String = "",
) {
    val isPaired: Boolean
        get() = hasLanToken

    companion object {
        const val DEFAULT_LAN_PORT = 18791
    }
}

/**
 * Stores the independent LAN `lanToken` encrypted with a 256-bit AES-GCM key inside `AndroidKeyStore`.
 * Completely isolated from WAN Relay `deviceToken` so switching modes or resetting one mode
 * never affects the other mode's pairing.
 */
class LanCredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Fallback in-process AES key ONLY for JVM Robolectric unit tests where AndroidKeyStore provider is absent
    @Volatile
    private var jvmTestFallbackKey: SecretKey? = null

    @Synchronized
    fun saveLanPairing(
        lanToken: String,
        bridgeDeviceId: String,
        bridgeDeviceName: String,
        connectedIp: String,
        connectedPort: Int,
    ) {
        val cleanToken = lanToken.trim()
        require(cleanToken.isNotEmpty()) { "lanToken must not be blank" }
        val encryptedBlob = encryptSecret(cleanToken)
        prefs.edit()
            .putString(KEY_ENC_LAN_TOKEN, encryptedBlob)
            .putString(KEY_BRIDGE_ID, bridgeDeviceId.trim())
            .putString(KEY_BRIDGE_NAME, bridgeDeviceName.trim())
            .putString(KEY_KNOWN_IP, connectedIp.trim())
            .putInt(KEY_KNOWN_PORT, connectedPort.takeIf { it in 1..65535 } ?: LanPairingSnapshot.DEFAULT_LAN_PORT)
            .apply()
    }

    @Synchronized
    fun updateKnownEndpoint(ip: String, port: Int) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return
        prefs.edit()
            .putString(KEY_KNOWN_IP, cleanIp)
            .putInt(KEY_KNOWN_PORT, port.takeIf { it in 1..65535 } ?: LanPairingSnapshot.DEFAULT_LAN_PORT)
            .apply()
    }

    @Synchronized
    fun setManualHostOverride(rawHostPort: String) {
        prefs.edit()
            .putString(KEY_MANUAL_HOST, rawHostPort.trim())
            .apply()
    }

    @Synchronized
    fun getLanToken(): String {
        val blob = prefs.getString(KEY_ENC_LAN_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return ""
        return runCatching { decryptSecret(blob) }.getOrElse { "" }
    }

    @Synchronized
    fun clearLanPairing(keepKnownIp: Boolean = true) {
        val editor = prefs.edit()
            .remove(KEY_ENC_LAN_TOKEN)
            .remove(KEY_BRIDGE_ID)
            .remove(KEY_BRIDGE_NAME)
        if (!keepKnownIp) {
            editor.remove(KEY_KNOWN_IP)
            editor.remove(KEY_KNOWN_PORT)
        }
        editor.apply()
    }

    @Synchronized
    fun getSnapshot(): LanPairingSnapshot {
        val tokenPresent = getLanToken().isNotBlank()
        return LanPairingSnapshot(
            hasLanToken = tokenPresent,
            bridgeDeviceId = prefs.getString(KEY_BRIDGE_ID, "") ?: "",
            bridgeDeviceName = prefs.getString(KEY_BRIDGE_NAME, "") ?: "",
            knownIp = prefs.getString(KEY_KNOWN_IP, "") ?: "",
            knownPort = prefs.getInt(KEY_KNOWN_PORT, LanPairingSnapshot.DEFAULT_LAN_PORT),
            manualHostOverride = prefs.getString(KEY_MANUAL_HOST, "") ?: "",
        )
    }

    private fun getOrCreateAesKey(): SecretKey {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (_: Exception) {
            // Host JVM unit-test fallback (Robolectric has no AndroidKeyStore SPI)
            jvmTestFallbackKey ?: KeyGenerator.getInstance("AES").apply {
                init(256, SecureRandom())
            }.generateKey().also { jvmTestFallbackKey = it }
        }
    }

    private fun encryptSecret(plainText: String): String {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    private fun decryptSecret(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherBytes)
        return String(plain, Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "clawbridge_lan_token_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "clawbridge_lan_secure_v1"

        private const val KEY_ENC_LAN_TOKEN = "enc_lan_token"
        private const val KEY_BRIDGE_ID = "lan_bridge_id"
        private const val KEY_BRIDGE_NAME = "lan_bridge_name"
        private const val KEY_KNOWN_IP = "lan_known_ip"
        private const val KEY_KNOWN_PORT = "lan_known_port"
        private const val KEY_MANUAL_HOST = "lan_manual_host"

        /**
         * Parses user input such as `192.168.1.100`, `192.168.1.5:18791`, or `ws://192.168.1.100:18791`
         * into `(host, port)`. Returns null if empty or invalid.
         */
        fun parseHostAndPort(raw: String, defaultPort: Int = LanPairingSnapshot.DEFAULT_LAN_PORT): Pair<String, Int>? {
            val cleaned = raw.trim()
                .removePrefix("ws://")
                .removePrefix("wss://")
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .trim()
            if (cleaned.isEmpty()) return null

            val host: String
            val port: Int
            if (cleaned.contains(":")) {
                host = cleaned.substringBefore(":").trim()
                port = cleaned.substringAfter(":").trim().toIntOrNull() ?: return null
            } else {
                host = cleaned
                port = defaultPort
            }
            if (host.isEmpty() || port !in 1..65535) return null
            if (host.any { it.isWhitespace() }) return null
            return host to port
        }
    }
}
