package com.clawbridge.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.clawbridge.mobile.data.LanCredentialStore
import com.clawbridge.mobile.data.LanPairingSnapshot
import com.clawbridge.mobile.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Dual-Mode Connection Coordinator (`ConnectionTransport` facade).
 *
 * Strictly enforces:
 *   - Single active transport at any time (`RelayTransport` OR `LanTransport` — never both simultaneously)
 *   - Persists user-selected mode (`ConnectionMode.LAN` / `ConnectionMode.WAN`) across App restarts
 *   - Zero silent fallback: mode switch is always explicit and reflected in `uiState`
 *   - Independent pairing credentials (`deviceToken` in DataStore vs `lanToken` in Android Keystore)
 */
class RelaySocketClient(
    context: Context,
    private val settingsStore: SettingsStore,
    val lanCredentialStore: LanCredentialStore = LanCredentialStore(context),
) : ConnectionTransport {

    companion object {
        const val PROTOCOL_VERSION = 1
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val relayTransport = RelayTransport(appContext, settingsStore)
    val lanTransport = LanTransport(appContext, settingsStore, lanCredentialStore)

    @Volatile
    private var activeMode: ConnectionMode = ConnectionMode.WAN

    @Volatile
    private var started = false

    private var networkCallbackRegistered = false

    private val _uiState = MutableStateFlow(ConnectionUiState())
    override val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayFrame>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingFrames: SharedFlow<RelayFrame> = _incomingFrames.asSharedFlow()

    init {
        // Forward state & business frames ONLY from whichever transport is currently active
        scope.launch {
            relayTransport.uiState.collect { wanState ->
                if (activeMode == ConnectionMode.WAN) {
                    _uiState.value = wanState.copy(connectionMode = ConnectionMode.WAN)
                }
            }
        }
        scope.launch {
            lanTransport.uiState.collect { lanState ->
                if (activeMode == ConnectionMode.LAN) {
                    _uiState.value = lanState.copy(connectionMode = ConnectionMode.LAN)
                }
            }
        }
        scope.launch {
            relayTransport.incomingFrames.collect { frame ->
                if (activeMode == ConnectionMode.WAN) {
                    _incomingFrames.tryEmit(frame)
                }
            }
        }
        scope.launch {
            lanTransport.incomingFrames.collect { frame ->
                if (activeMode == ConnectionMode.LAN) {
                    _incomingFrames.tryEmit(frame)
                }
            }
        }
    }

    private fun currentDelegate(): ConnectionTransport =
        if (activeMode == ConnectionMode.LAN) lanTransport else relayTransport

    override fun start() {
        registerNetworkMonitor()
        if (started) {
            currentDelegate().start()
            return
        }
        started = true
        scope.launch {
            val savedCfg = settingsStore.settingsFlow.first()
            activeMode = savedCfg.connectionMode
            if (activeMode == ConnectionMode.LAN) {
                relayTransport.stop()
                _uiState.value = lanTransport.uiState.value.copy(connectionMode = ConnectionMode.LAN)
                lanTransport.start()
            } else {
                lanTransport.stop()
                _uiState.value = relayTransport.uiState.value.copy(connectionMode = ConnectionMode.WAN)
                relayTransport.start()
            }
        }
    }

    override fun stop() {
        started = false
        lanTransport.stop()
        relayTransport.stop()
    }

    fun switchMode(targetMode: ConnectionMode) {
        scope.launch {
            settingsStore.setConnectionMode(targetMode)
            val changing = activeMode != targetMode
            activeMode = targetMode

            if (targetMode == ConnectionMode.LAN) {
                relayTransport.stop()
                val snap = lanCredentialStore.getSnapshot()
                _uiState.value = ConnectionUiState(
                    connectionMode = ConnectionMode.LAN,
                    socketStatus = SocketStatus.CONNECTING,
                    paired = snap.isPaired,
                    lanKnownIp = snap.knownIp,
                    lanManualHost = snap.manualHostOverride,
                    lanPairedDeviceName = snap.bridgeDeviceName,
                    networkTypeLabel = "Wi-Fi 局域网",
                    modeSwitchingHint = "已切换至内网直连，正在发现局域网电脑...",
                )
                lanTransport.start()
            } else {
                lanTransport.stop()
                val cfg = settingsStore.settingsFlow.first()
                _uiState.value = ConnectionUiState(
                    connectionMode = ConnectionMode.WAN,
                    socketStatus = SocketStatus.CONNECTING,
                    relayUrl = cfg.relayUrl,
                    paired = cfg.isPaired,
                    modeSwitchingHint = if (changing) "已切换至外网中继，正在重连..." else null,
                )
                relayTransport.start()
            }
        }
    }

    fun connectLanManualHost(rawHostPort: String) {
        scope.launch {
            if (activeMode != ConnectionMode.LAN) {
                settingsStore.setConnectionMode(ConnectionMode.LAN)
                activeMode = ConnectionMode.LAN
                relayTransport.stop()
            }
            lanTransport.connectWithManualHost(rawHostPort)
        }
    }

    fun getLanPairingSnapshot(): LanPairingSnapshot = lanCredentialStore.getSnapshot()

    fun unpairLanOnly() {
        lanTransport.unpairAndReset()
    }

    fun unpairWanOnly() {
        relayTransport.unpairAndReset()
    }

    override fun reconnectNow() {
        currentDelegate().reconnectNow()
    }

    override fun sendPingNow() {
        currentDelegate().sendPingNow()
    }

    override fun pulseFromDoze() {
        currentDelegate().pulseFromDoze()
    }

    override fun submitPairingCode(code: String) {
        currentDelegate().submitPairingCode(code)
    }

    override fun unpairAndReset() {
        currentDelegate().unpairAndReset()
    }

    fun applyNewRelayConfig(newUrl: String, trustSelfSigned: Boolean) {
        relayTransport.applyNewRelayConfig(newUrl, trustSelfSigned)
    }

    override fun sendEnvelope(type: String, payload: JsonObject, reqId: String): Boolean {
        return currentDelegate().sendEnvelope(type, payload, reqId)
    }

    private fun registerNetworkMonitor() {
        if (networkCallbackRegistered) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (activeMode == ConnectionMode.LAN) {
                        lanTransport.notifyNetworkChanged()
                    } else {
                        relayTransport.notifyNetworkChanged()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (activeMode == ConnectionMode.LAN) {
                        lanTransport.notifyNetworkChanged()
                    } else {
                        relayTransport.notifyNetworkChanged()
                    }
                }

                override fun onLost(network: Network) {
                    if (activeMode == ConnectionMode.LAN) {
                        lanTransport.notifyNetworkChanged()
                    } else {
                        relayTransport.notifyNetworkChanged()
                    }
                }
            })
            networkCallbackRegistered = true
        }
    }
}
