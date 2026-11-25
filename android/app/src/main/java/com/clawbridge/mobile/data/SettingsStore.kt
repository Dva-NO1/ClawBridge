package com.clawbridge.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clawbridge_settings")

data class AppSettings(
    val relayUrl: String = DEFAULT_RELAY_URL,
    val deviceId: String = "",
    val deviceToken: String = "",
    val pairedBridgeId: String = "",
    val trustSelfSigned: Boolean = false,
    val pinHash: String = "",
    val pinSalt: String = "",
    val requirePinForSend: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val dndEnabled: Boolean = false,
    val dndStart: String = "23:30",
    val dndEnd: String = "07:00",
    val morningSchedule: String = "07:30",
    val nightSchedule: String = "23:00",
    val pinnedSessionIds: Set<String> = emptySet(),
) {
    val isPaired: Boolean
        get() = deviceToken.isNotBlank() && pairedBridgeId.isNotBlank()

    val hasPin: Boolean
        get() = pinHash.isNotBlank() && pinSalt.isNotBlank()

    val isRelayConfigured: Boolean
        get() = relayUrl.isNotBlank() && !relayUrl.contains("example.com", ignoreCase = true)

    companion object {
        const val DEFAULT_RELAY_URL = "wss://relay.example.com"
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val PAIRED_BRIDGE_ID = stringPreferencesKey("paired_bridge_id")
        val TRUST_SELF_SIGNED = booleanPreferencesKey("trust_self_signed")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val REQUIRE_PIN_FOR_SEND = booleanPreferencesKey("require_pin_for_send")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        val DND_START = stringPreferencesKey("dnd_start")
        val DND_END = stringPreferencesKey("dnd_end")
        val MORNING_SCHEDULE = stringPreferencesKey("morning_schedule")
        val NIGHT_SCHEDULE = stringPreferencesKey("night_schedule")
        val PINNED_SESSIONS = stringSetPreferencesKey("pinned_session_ids")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            relayUrl = p[Keys.RELAY_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_RELAY_URL,
            deviceId = p[Keys.DEVICE_ID] ?: "",
            deviceToken = p[Keys.DEVICE_TOKEN] ?: "",
            pairedBridgeId = p[Keys.PAIRED_BRIDGE_ID] ?: "",
            trustSelfSigned = p[Keys.TRUST_SELF_SIGNED] ?: false,
            pinHash = p[Keys.PIN_HASH] ?: "",
            pinSalt = p[Keys.PIN_SALT] ?: "",
            requirePinForSend = p[Keys.REQUIRE_PIN_FOR_SEND] ?: false,
            notificationsEnabled = p[Keys.NOTIFICATIONS_ENABLED] ?: true,
            dndEnabled = p[Keys.DND_ENABLED] ?: false,
            dndStart = p[Keys.DND_START] ?: "23:30",
            dndEnd = p[Keys.DND_END] ?: "07:00",
            morningSchedule = p[Keys.MORNING_SCHEDULE] ?: "07:30",
            nightSchedule = p[Keys.NIGHT_SCHEDULE] ?: "23:00",
            pinnedSessionIds = p[Keys.PINNED_SESSIONS] ?: emptySet(),
        )
    }

    suspend fun togglePinSession(sessionId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.PINNED_SESSIONS] ?: emptySet()
            p[Keys.PINNED_SESSIONS] = if (sessionId in cur) cur - sessionId else cur + sessionId
        }
    }

    // ─── Per-Session Reading Position Memory (Last-Seen Chat Position) ────────
    private val scrollPrefs by lazy {
        context.getSharedPreferences("clawbridge_chat_scroll_pos", Context.MODE_PRIVATE)
    }

    fun saveSessionScrollPosition(sessionId: String, anchorMessageId: String, index: Int, offset: Int) {
        if (sessionId.isBlank() || index < 0) return
        scrollPrefs.edit()
            .putString("pos_$sessionId", "$index|$offset|$anchorMessageId")
            .apply()
    }

    fun getSessionScrollPosition(sessionId: String): SavedScrollPosition? {
        if (sessionId.isBlank()) return null
        val raw = scrollPrefs.getString("pos_$sessionId", null) ?: return null
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return null
        val idx = parts[0].toIntOrNull() ?: return null
        val off = parts[1].toIntOrNull() ?: 0
        return SavedScrollPosition(
            anchorMessageId = parts[2],
            index = idx.coerceAtLeast(0),
            offset = off.coerceAtLeast(0)
        )
    }

    suspend fun ensureDeviceId(): String {
        val cur = settingsFlow.first()
        if (cur.deviceId.isNotBlank()) return cur.deviceId
        val generated = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    /**
     * Spec 5.5: Saving a changed relayUrl clears old pairing credentials so the app
     * disconnects from the old relay, connects to the new relay, and enters re-pairing cleanly.
     */
    suspend fun updateRelayConfig(newUrlRaw: String, trustSelfSigned: Boolean): Boolean {
        val normalized = normalizeRelayUrl(newUrlRaw)
        var urlChanged = false
        context.dataStore.edit { p ->
            val prevUrl = p[Keys.RELAY_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_RELAY_URL
            urlChanged = prevUrl != normalized
            p[Keys.RELAY_URL] = normalized
            p[Keys.TRUST_SELF_SIGNED] = trustSelfSigned
            if (urlChanged) {
                p[Keys.DEVICE_TOKEN] = ""
                p[Keys.PAIRED_BRIDGE_ID] = ""
                p[Keys.DEVICE_ID] = UUID.randomUUID().toString()
            }
        }
        return urlChanged
    }

    suspend fun savePairingCredentials(token: String, bridgeDeviceId: String) {
        context.dataStore.edit { p ->
            p[Keys.DEVICE_TOKEN] = token
            p[Keys.PAIRED_BRIDGE_ID] = bridgeDeviceId
        }
    }

    suspend fun clearPairing() {
        context.dataStore.edit { p ->
            p[Keys.DEVICE_TOKEN] = ""
            p[Keys.PAIRED_BRIDGE_ID] = ""
        }
    }

    suspend fun setPin(pin: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }
        val hashHex = hashPin(pin, saltHex)
        context.dataStore.edit { p ->
            p[Keys.PIN_SALT] = saltHex
            p[Keys.PIN_HASH] = hashHex
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val cur = settingsFlow.first()
        if (!cur.hasPin) return false
        return hashPin(pin, cur.pinSalt) == cur.pinHash
    }

    suspend fun updatePreferences(
        requirePinForSend: Boolean? = null,
        notificationsEnabled: Boolean? = null,
        dndEnabled: Boolean? = null,
        dndStart: String? = null,
        dndEnd: String? = null,
        morningSchedule: String? = null,
        nightSchedule: String? = null,
    ) {
        context.dataStore.edit { p ->
            requirePinForSend?.let { p[Keys.REQUIRE_PIN_FOR_SEND] = it }
            notificationsEnabled?.let { p[Keys.NOTIFICATIONS_ENABLED] = it }
            dndEnabled?.let { p[Keys.DND_ENABLED] = it }
            dndStart?.let { p[Keys.DND_START] = it }
            dndEnd?.let { p[Keys.DND_END] = it }
            morningSchedule?.let { p[Keys.MORNING_SCHEDULE] = it }
            nightSchedule?.let { p[Keys.NIGHT_SCHEDULE] = it }
        }
    }

    companion object {
        fun normalizeRelayUrl(raw: String): String {
            val t = raw.trim().removeSuffix("/")
            if (t.isEmpty()) return AppSettings.DEFAULT_RELAY_URL
            return when {
                t.startsWith("wss://", ignoreCase = true) -> "wss://" + t.substring(6)
                t.startsWith("https://", ignoreCase = true) -> "wss://" + t.substring(8)
                t.startsWith("ws://", ignoreCase = true) -> "ws://" + t.substring(5)
                t.startsWith("http://", ignoreCase = true) -> "ws://" + t.substring(7)
                else -> "wss://$t"
            }
        }

        private fun hashPin(pin: String, saltHex: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("$saltHex:$pin".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
