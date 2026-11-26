package com.clawbridge.mobile.net

import com.clawbridge.mobile.data.AppSettings
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class ConnectionMode(val wireValue: String, val displayLabel: String) {
    LAN("lan", "内网直连"),
    WAN("wan", "外网中继");

    companion object {
        fun fromWire(raw: String?): ConnectionMode =
            if (raw?.trim()?.equals("lan", ignoreCase = true) == true) LAN else WAN
    }
}

enum class SocketStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ConnectionUiState(
    val connectionMode: ConnectionMode = ConnectionMode.WAN,
    val socketStatus: SocketStatus = SocketStatus.DISCONNECTED,
    val relayUrl: String = AppSettings.DEFAULT_RELAY_URL,
    val paired: Boolean = false,
    val desktopOnline: Boolean = false,
    val latencyMs: Long? = null,
    val networkTypeLabel: String = "检测中",
    val protocolError: String? = null,
    val lastError: String? = null,
    val pairingBusy: Boolean = false,
    val pairingError: String? = null,
    val pairingSuccessHint: Boolean = false,
    // LAN Direct specific presentation fields
    val lanConnectedHost: String = "",
    val lanKnownIp: String = "",
    val lanManualHost: String = "",
    val lanDiscoveredDeviceName: String = "",
    val lanPairedDeviceName: String = "",
    val lanDiscoveryFailed: Boolean = false,
    val lanServerOffSuspected: Boolean = false,
    val modeSwitchingHint: String? = null,
)

data class RelayFrame(
    val type: String,
    val reqId: String,
    val ts: Long,
    val payload: JsonObject
)

/**
 * Transport-agnostic client contract consumed by `MonitorRepository`, `NotificationCenter`,
 * `RelayForegroundService`, and Compose UI screens.
 *
 * Implementations:
 *   - `RelayTransport`: WAN WSS Relay (`wss://...`)
 *   - `LanTransport`: LAN Direct WebSocket (`ws://<ip>:<lanPort>`) + UDP 18792 discovery
 *   - `RelaySocketClient` (Dual-Mode Coordinator): single-active-mode facade switching cleanly between them
 */
interface ConnectionTransport {
    val uiState: StateFlow<ConnectionUiState>
    val incomingFrames: SharedFlow<RelayFrame>

    fun start()
    fun stop()
    fun reconnectNow()
    fun sendPingNow()
    fun pulseFromDoze()
    fun submitPairingCode(code: String)
    fun unpairAndReset()
    fun sendEnvelope(
        type: String,
        payload: JsonObject,
        reqId: String = UUID.randomUUID().toString()
    ): Boolean
}
