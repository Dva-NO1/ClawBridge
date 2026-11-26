package com.clawbridge.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.clawbridge.mobile.data.LanCredentialStore
import com.clawbridge.mobile.data.LanPairingSnapshot
import com.clawbridge.mobile.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DiscoveredBridgeEndpoint(
    val host: String,
    val port: Int,
    val deviceId: String = "",
    val deviceName: String = "",
)

/**
 * LAN Direct WebSocket Transport (`ws://<ip>:<lanPort>` + UDP 18792 Broadcast Discovery).
 *
 * Strictly obeys P4 & Iron Rules:
 *   - Uses `Proxy.NO_PROXY` + `Dns.SYSTEM` so mobile VPNs/proxies never hijack LAN traffic
 *   - Paired devices try known LAN IP first (fast path), then UDP broadcast discovery (`CLAWBRIDGE_DISCOVER v1`,
 *     3s timeout × up to 2 retries) to handle DHCP IP changes
 *   - Unpaired devices enter 6-digit LAN pairing guide and store issued `lanToken` in Android Keystore
 *   - When PC LAN mode is off or unreachable, transitions cleanly to `SocketStatus.ERROR` with explicit
 *     mismatch & manual-IP guidance — NEVER spins infinitely and NEVER silently falls back to WAN
 */
class LanTransport(
    context: Context,
    private val settingsStore: SettingsStore,
    private val lanCredentialStore: LanCredentialStore,
    private val discoveryUdpPort: Int = DEFAULT_DISCOVERY_UDP_PORT,
) : ConnectionTransport {

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_DISCOVERY_UDP_PORT = 18792
        const val DISCOVER_MAGIC = "CLAWBRIDGE_DISCOVER v1"
        private const val DISCOVERY_TIMEOUT_MS = 3_000
        private const val DISCOVERY_MAX_RETRIES = 2
        private const val APP_PING_INTERVAL_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val lanHttpClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(Dns.SYSTEM)
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val probeHttpClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(Dns.SYSTEM)
        .connectTimeout(1600, TimeUnit.MILLISECONDS)
        .readTimeout(1600, TimeUnit.MILLISECONDS)
        .build()

    private val _uiState = MutableStateFlow(buildInitialState())
    override val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayFrame>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingFrames: SharedFlow<RelayFrame> = _incomingFrames.asSharedFlow()

    private var workerJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var currentEndpoint: DiscoveredBridgeEndpoint? = null
    @Volatile private var lastRxAtMs: Long = 0L
    @Volatile private var running = false

    private fun buildInitialState(): ConnectionUiState {
        val snap = lanCredentialStore.getSnapshot()
        return ConnectionUiState(
            connectionMode = ConnectionMode.LAN,
            socketStatus = SocketStatus.DISCONNECTED,
            paired = snap.isPaired,
            lanKnownIp = snap.knownIp,
            lanManualHost = snap.manualHostOverride,
            lanPairedDeviceName = snap.bridgeDeviceName,
            networkTypeLabel = "局域网直连",
        )
    }

    override fun start() {
        running = true
        if (workerJob?.isActive == true && activeSocket != null) return
        launchConnectAttempt(preferManualOnly = false)
    }

    override fun stop() {
        running = false
        pingJob?.cancel()
        pingJob = null
        workerJob?.cancel()
        workerJob = null
        val old = activeSocket
        activeSocket = null
        runCatching { old?.close(1000, "switched_to_wan") }
        val snap = lanCredentialStore.getSnapshot()
        _uiState.update {
            it.copy(
                socketStatus = SocketStatus.DISCONNECTED,
                desktopOnline = false,
                pairingBusy = false,
                paired = snap.isPaired,
                lanKnownIp = snap.knownIp,
                lanManualHost = snap.manualHostOverride,
                lanPairedDeviceName = snap.bridgeDeviceName,
            )
        }
    }

    override fun reconnectNow() {
        if (!running) return
        val old = activeSocket
        activeSocket = null
        runCatching { old?.cancel() }
        launchConnectAttempt(preferManualOnly = false)
    }

    fun connectWithManualHost(rawInput: String) {
        val trimmed = rawInput.trim()
        val parsed = LanCredentialStore.parseHostAndPort(trimmed)
        if (parsed == null) {
            _uiState.update {
                it.copy(
                    socketStatus = SocketStatus.ERROR,
                    lastError = "请输入有效的局域网 IP 地址（例如 192.168.1.100 或 192.168.1.5:18791）"
                )
            }
            return
        }
        lanCredentialStore.setManualHostOverride(trimmed)
        lanCredentialStore.updateKnownEndpoint(parsed.first, parsed.second)
        val old = activeSocket
        activeSocket = null
        runCatching { old?.cancel() }
        running = true
        launchConnectAttempt(preferManualOnly = true)
    }

    override fun sendPingNow() {
        sendEnvelope("ping", buildJsonObject { })
    }

    override fun pulseFromDoze() {
        if (!running) return
        val ws = activeSocket
        if (ws == null) return
        val beforeRx = lastRxAtMs
        if (!sendEnvelope("ping", buildJsonObject { })) {
            reconnectNow()
            return
        }
        scope.launch {
            delay(4_000L)
            if (running && lastRxAtMs <= beforeRx) {
                reconnectNow()
            }
        }
    }

    override fun submitPairingCode(code: String) {
        val trimmed = code.trim()
        if (!trimmed.matches(Regex("^\\d{6}$"))) {
            _uiState.update { it.copy(pairingError = "请输入桌面端显示的 6 位内网配对码", pairingBusy = false) }
            return
        }

        scope.launch {
            val deviceId = settingsStore.ensureDeviceId()
            val snap = lanCredentialStore.getSnapshot()
            val endpoint = currentEndpoint
                ?: LanCredentialStore.parseHostAndPort(snap.manualHostOverride)?.let { DiscoveredBridgeEndpoint(it.first, it.second) }
                ?: snap.knownIp.takeIf { it.isNotBlank() }?.let { DiscoveredBridgeEndpoint(it, snap.knownPort) }

            if (endpoint == null) {
                _uiState.update {
                    it.copy(
                        pairingBusy = false,
                        pairingError = "尚未发现电脑地址，请先确认同一 WiFi 点击重新扫描，或在下方输入电脑局域网 IP"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    pairingBusy = true,
                    pairingError = null,
                    pairingSuccessHint = false,
                    socketStatus = SocketStatus.CONNECTING,
                )
            }

            // Open WebSocket and immediately send `pair.request` as the first frame
            workerJob?.cancel()
            workerJob = scope.launch {
                openAuthenticatedOrPairingSocket(
                    endpoint = endpoint,
                    deviceId = deviceId,
                    lanToken = "",
                    pairCodeToSubmit = trimmed
                )
            }
        }
    }

    override fun unpairAndReset() {
        scope.launch {
            sendEnvelope("pair.unpair", buildJsonObject { })
            lanCredentialStore.clearLanPairing(keepKnownIp = true)
            val old = activeSocket
            activeSocket = null
            runCatching { old?.close(1000, "lan_unpaired") }
            val snap = lanCredentialStore.getSnapshot()
            _uiState.update {
                it.copy(
                    paired = false,
                    desktopOnline = false,
                    pairingBusy = false,
                    pairingError = null,
                    pairingSuccessHint = false,
                    lanPairedDeviceName = "",
                    lanKnownIp = snap.knownIp,
                )
            }
            if (running) {
                launchConnectAttempt(preferManualOnly = false)
            }
        }
    }

    fun notifyNetworkChanged() {
        _uiState.update { it.copy(networkTypeLabel = detectNetworkLabel()) }
        if (running && activeSocket == null) {
            launchConnectAttempt(preferManualOnly = false)
        }
    }

    override fun sendEnvelope(
        type: String,
        payload: JsonObject,
        reqId: String
    ): Boolean {
        val ws = activeSocket ?: return false
        val frame = buildJsonObject {
            put("v", JsonPrimitive(PROTOCOL_VERSION))
            put("type", JsonPrimitive(type))
            put("reqId", JsonPrimitive(reqId))
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            put("payload", payload)
        }
        return ws.send(frame.toString())
    }

    private fun reportClientModeToBridge() {
        sendEnvelope(
            "client.mode",
            buildJsonObject {
                put("mode", JsonPrimitive(ConnectionMode.LAN.wireValue))
            }
        )
    }

    private fun launchConnectAttempt(preferManualOnly: Boolean) {
        workerJob?.cancel()
        workerJob = scope.launch {
            val deviceId = settingsStore.ensureDeviceId()
            val snap = lanCredentialStore.getSnapshot()
            val lanToken = lanCredentialStore.getLanToken()

            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.LAN,
                    socketStatus = SocketStatus.CONNECTING,
                    paired = lanToken.isNotBlank(),
                    desktopOnline = false,
                    lanKnownIp = snap.knownIp,
                    lanManualHost = snap.manualHostOverride,
                    lanPairedDeviceName = snap.bridgeDeviceName,
                    lanDiscoveryFailed = false,
                    lanServerOffSuspected = false,
                    lastError = null,
                    networkTypeLabel = detectNetworkLabel(),
                    modeSwitchingHint = "正在扫描并直连局域网电脑...",
                )
            }

            // 1. Fast Path: Known IP (for paired devices) OR user-entered Manual Host Override
            val fastCandidate: Pair<String, Int>? =
                LanCredentialStore.parseHostAndPort(snap.manualHostOverride)
                    ?: snap.knownIp.takeIf { it.isNotBlank() }?.let { it to snap.knownPort }

            if (fastCandidate != null) {
                val (fastHost, fastPort) = fastCandidate
                val probed = probeHealthz(fastHost, fastPort)
                if (!running) return@launch
                if (probed != null) {
                    currentEndpoint = probed
                    lanCredentialStore.updateKnownEndpoint(probed.host, probed.port)
                    if (lanToken.isNotBlank()) {
                        openAuthenticatedOrPairingSocket(
                            endpoint = probed,
                            deviceId = deviceId,
                            lanToken = lanToken,
                            pairCodeToSubmit = null
                        )
                        return@launch
                    } else {
                        // Unpaired: endpoint verified reachable! Show 6-digit pairing guide cleanly
                        _uiState.update {
                            it.copy(
                                socketStatus = SocketStatus.CONNECTED,
                                paired = false,
                                desktopOnline = false,
                                lanConnectedHost = "${probed.host}:${probed.port}",
                                lanKnownIp = probed.host,
                                lanDiscoveredDeviceName = probed.deviceName,
                                lanDiscoveryFailed = false,
                                lanServerOffSuspected = false,
                                lastError = null,
                                modeSwitchingHint = null,
                            )
                        }
                        return@launch
                    }
                } else if (preferManualOnly) {
                    _uiState.update {
                        it.copy(
                            socketStatus = SocketStatus.ERROR,
                            desktopOnline = false,
                            lanDiscoveryFailed = true,
                            lanServerOffSuspected = true,
                            modeSwitchingHint = null,
                            lastError = "无法连接到 $fastHost:$fastPort，请确认电脑端已切至「内网直连」且处于同一 WiFi"
                        )
                    }
                    return@launch
                }
            }

            // 2. UDP Broadcast Discovery (3s timeout, max 2 retries -> handles first-time connect & DHCP IP changes)
            var discovered: DiscoveredBridgeEndpoint? = null
            for (attempt in 0..DISCOVERY_MAX_RETRIES) {
                if (!running) return@launch
                _uiState.update {
                    it.copy(
                        socketStatus = SocketStatus.CONNECTING,
                        modeSwitchingHint = if (attempt == 0) {
                            "正在局域网广播发现电脑 (UDP $discoveryUdpPort)..."
                        } else {
                            "正在重试局域网发现 (${attempt}/$DISCOVERY_MAX_RETRIES)..."
                        }
                    )
                }
                discovered = discoverViaUdpOnce(DISCOVERY_TIMEOUT_MS)
                if (discovered != null) break
            }

            if (!running) return@launch

            if (discovered == null) {
                // Both known IP and UDP discovery failed -> Clear non-spinning ERROR state (P4.2 + P4.5 + P5.4)
                _uiState.update {
                    it.copy(
                        socketStatus = SocketStatus.ERROR,
                        desktopOnline = false,
                        lanDiscoveryFailed = true,
                        lanServerOffSuspected = true,
                        modeSwitchingHint = null,
                        lastError = "未发现电脑，请确认同一 WiFi 或手动输入 IP（电脑端需开启「内网直连」模式）"
                    )
                }
                return@launch
            }

            currentEndpoint = discovered
            lanCredentialStore.updateKnownEndpoint(discovered.host, discovered.port)

            if (lanToken.isBlank()) {
                // Discovered PC while unpaired -> Display 6-digit LAN pairing guide!
                _uiState.update {
                    it.copy(
                        socketStatus = SocketStatus.CONNECTED,
                        paired = false,
                        desktopOnline = false,
                        lanConnectedHost = "${discovered.host}:${discovered.port}",
                        lanKnownIp = discovered.host,
                        lanDiscoveredDeviceName = discovered.deviceName,
                        lanDiscoveryFailed = false,
                        lanServerOffSuspected = false,
                        lastError = null,
                        modeSwitchingHint = null,
                    )
                }
                return@launch
            }

            openAuthenticatedOrPairingSocket(
                endpoint = discovered,
                deviceId = deviceId,
                lanToken = lanToken,
                pairCodeToSubmit = null
            )
        }
    }

    private suspend fun probeHealthz(host: String, port: Int): DiscoveredBridgeEndpoint? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://$host:$port/healthz")
                .get()
                .build()
            probeHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val obj = json.parseToJsonElement(body).jsonObject
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val enabled = obj["lanEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok || !enabled) return@use null
                val name = obj["deviceName"]?.jsonPrimitive?.contentOrNull ?: ""
                DiscoveredBridgeEndpoint(host = host, port = port, deviceName = name)
            }
        }.getOrNull()
    }

    private suspend fun discoverViaUdpOnce(timeoutMs: Int): DiscoveredBridgeEndpoint? = withContext(Dispatchers.IO) {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = runCatching {
            wm?.createMulticastLock("ClawBridge:LanUdpDiscover")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = (timeoutMs / 2).coerceAtLeast(800)
            }
            val payloadBytes = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
            val targets = collectBroadcastTargets()

            val deadline = System.currentTimeMillis() + timeoutMs
            var sentCount = 0
            val recvBuf = ByteArray(2048)

            while (running && System.currentTimeMillis() < deadline) {
                if (sentCount < 2) {
                    for (addr in targets) {
                        runCatching {
                            sock.send(DatagramPacket(payloadBytes, payloadBytes.size, addr, discoveryUdpPort))
                        }
                    }
                    sentCount++
                }

                val pkt = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    sock.receive(pkt)
                    val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8).trim()
                    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    val lanEnabled = obj["lanEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!lanEnabled) continue
                    val port = obj["lanPort"]?.jsonPrimitive?.intOrNull ?: LanPairingSnapshot.DEFAULT_LAN_PORT
                    val devId = obj["deviceId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val devName = obj["deviceName"]?.jsonPrimitive?.contentOrNull ?: "ClawBridge-PC"
                    val senderIp = pkt.address?.hostAddress?.takeIf { it.isNotBlank() } ?: continue
                    return@withContext DiscoveredBridgeEndpoint(
                        host = senderIp,
                        port = port,
                        deviceId = devId,
                        deviceName = devName,
                    )
                } catch (_: SocketTimeoutException) {
                    // Loop if still before deadline (sends 2nd burst at midpoint)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sock?.close() }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) {
                        result.add(bcast)
                    }
                }
            }
        }
        return result.toList()
    }

    private suspend fun openAuthenticatedOrPairingSocket(
        endpoint: DiscoveredBridgeEndpoint,
        deviceId: String,
        lanToken: String,
        pairCodeToSubmit: String?,
    ) {
        val closedGate = CompletableDeferred<Unit>()
        val wsUrl = "ws://${endpoint.host}:${endpoint.port}"
        val req = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    socketStatus = SocketStatus.ERROR,
                    pairingBusy = false,
                    modeSwitchingHint = null,
                    lastError = "内网直连地址无效: ${e.message}"
                )
            }
            return
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!running) {
                    webSocket.close(1000, "stopped")
                    if (!closedGate.isCompleted) closedGate.complete(Unit)
                    return
                }
                activeSocket = webSocket
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

                if (!pairCodeToSubmit.isNullOrBlank()) {
                    sendEnvelope(
                        "pair.request",
                        buildJsonObject {
                            put("code", JsonPrimitive(pairCodeToSubmit))
                            put("deviceId", JsonPrimitive(deviceId))
                            put("deviceName", JsonPrimitive(deviceName))
                        }
                    )
                } else {
                    sendEnvelope(
                        "auth",
                        buildJsonObject {
                            put("deviceId", JsonPrimitive(deviceId))
                            put("lanToken", JsonPrimitive(lanToken))
                            put("deviceName", JsonPrimitive(deviceName))
                            put("mode", JsonPrimitive(ConnectionMode.LAN.wireValue))
                        }
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRxAtMs = System.currentTimeMillis()
                acquireBriefWakeLock()
                handleIncomingLanText(endpoint, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code.coerceIn(1000, 4999), reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeSocket === webSocket) activeSocket = null
                pingJob?.cancel()
                pingJob = null

                if (running) {
                    when (code) {
                        1001 -> {
                            // Desktop explicitly stopped LAN Direct server (`lan_server_stopped`) -> P5.4 immediate clear error!
                            _uiState.update {
                                it.copy(
                                    socketStatus = SocketStatus.ERROR,
                                    desktopOnline = false,
                                    pairingBusy = false,
                                    lanServerOffSuspected = true,
                                    lanDiscoveryFailed = true,
                                    modeSwitchingHint = null,
                                    lastError = "电脑端已关闭内网直连模式，请在电脑端重新开启或切回外网模式"
                                )
                            }
                        }
                        4029 -> {
                            _uiState.update {
                                it.copy(
                                    socketStatus = SocketStatus.ERROR,
                                    desktopOnline = false,
                                    pairingBusy = false,
                                    modeSwitchingHint = null,
                                    lastError = it.pairingError ?: it.lastError ?: "连续验证失败次数过多，该 IP 已被锁定 5 分钟"
                                )
                            }
                        }
                        4003, 4004 -> {
                            // Token rejected or desktop unpaired -> show pairing guide cleanly
                            val curErr = _uiState.value.pairingError ?: _uiState.value.lastError
                            _uiState.update {
                                it.copy(
                                    socketStatus = SocketStatus.CONNECTED,
                                    paired = lanCredentialStore.getSnapshot().isPaired,
                                    desktopOnline = false,
                                    pairingBusy = false,
                                    modeSwitchingHint = null,
                                    lastError = curErr
                                )
                            }
                        }
                        else -> {
                            // If pairing just failed with wrong code (< 5 attempts), keep socketStatus CONNECTED so user can re-enter code
                            if (!pairCodeToSubmit.isNullOrBlank() && !lanCredentialStore.getSnapshot().isPaired) {
                                _uiState.update {
                                    it.copy(
                                        socketStatus = SocketStatus.CONNECTED,
                                        desktopOnline = false,
                                        pairingBusy = false,
                                        modeSwitchingHint = null,
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        socketStatus = SocketStatus.ERROR,
                                        desktopOnline = false,
                                        pairingBusy = false,
                                        lanServerOffSuspected = true,
                                        modeSwitchingHint = null,
                                        lastError = "内网直连已断开（请确认电脑端内网服务仍在运行）"
                                    )
                                }
                            }
                        }
                    }
                }
                if (!closedGate.isCompleted) closedGate.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeSocket === webSocket) activeSocket = null
                pingJob?.cancel()
                pingJob = null
                if (running) {
                    _uiState.update {
                        it.copy(
                            socketStatus = SocketStatus.ERROR,
                            desktopOnline = false,
                            pairingBusy = false,
                            lanServerOffSuspected = true,
                            lanDiscoveryFailed = true,
                            modeSwitchingHint = null,
                            lastError = "无法直连电脑 (${endpoint.host}:${endpoint.port})：${t.message ?: "连接失败"}。请确认电脑端已开启内网模式"
                        )
                    }
                }
                if (!closedGate.isCompleted) closedGate.complete(Unit)
            }
        }

        activeSocket = lanHttpClient.newWebSocket(req, listener)
        closedGate.await()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(200)
            sendEnvelope("ping", buildJsonObject { })
            while (running && activeSocket != null) {
                delay(APP_PING_INTERVAL_MS)
                sendEnvelope("ping", buildJsonObject { })
            }
        }
    }

    private fun handleIncomingLanText(endpoint: DiscoveredBridgeEndpoint, text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val version = obj["v"]?.jsonPrimitive?.intOrNull
        val reqId = obj["reqId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        if (version != PROTOCOL_VERSION) {
            val msg = "协议版本不匹配：手机端 v=$PROTOCOL_VERSION，收到 v=${version ?: "null"}"
            _uiState.update { it.copy(protocolError = msg, lastError = msg) }
            return
        }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val payload = (obj["payload"] as? JsonObject) ?: buildJsonObject { }

        when (type) {
            "auth.ack" -> {
                val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok) return
                val bridgeName = payload["bridgeDeviceName"]?.jsonPrimitive?.contentOrNull
                    ?: endpoint.deviceName.ifBlank { "ClawBridge-PC" }
                lanCredentialStore.updateKnownEndpoint(endpoint.host, endpoint.port)
                _uiState.update {
                    it.copy(
                        socketStatus = SocketStatus.CONNECTED,
                        paired = true,
                        desktopOnline = true,
                        lanConnectedHost = "${endpoint.host}:${endpoint.port}",
                        lanKnownIp = endpoint.host,
                        lanPairedDeviceName = bridgeName,
                        lanDiscoveredDeviceName = bridgeName,
                        lanDiscoveryFailed = false,
                        lanServerOffSuspected = false,
                        protocolError = null,
                        lastError = null,
                        pairingError = null,
                        pairingBusy = false,
                        modeSwitchingHint = null,
                        networkTypeLabel = detectNetworkLabel(),
                    )
                }
                startPingLoop()
                reportClientModeToBridge()
            }

            "auth.error" -> {
                val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: ""
                val msg = payload["message"]?.jsonPrimitive?.contentOrNull ?: "内网鉴权失败"
                if (code == "invalid_lan_token") {
                    lanCredentialStore.clearLanPairing(keepKnownIp = true)
                    _uiState.update {
                        it.copy(
                            paired = false,
                            desktopOnline = false,
                            pairingBusy = false,
                            pairingError = msg,
                            lastError = msg,
                            modeSwitchingHint = null,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            desktopOnline = false,
                            pairingBusy = false,
                            pairingError = msg,
                            lastError = msg,
                            modeSwitchingHint = null,
                        )
                    }
                }
            }

            "pair.result" -> {
                val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (success) {
                    val issuedToken = payload["lanToken"]?.jsonPrimitive?.contentOrNull ?: ""
                    val bridgeId = payload["bridgeDeviceId"]?.jsonPrimitive?.contentOrNull ?: endpoint.deviceId
                    val bridgeName = payload["bridgeDeviceName"]?.jsonPrimitive?.contentOrNull
                        ?: endpoint.deviceName.ifBlank { "ClawBridge-PC" }
                    if (issuedToken.isNotBlank()) {
                        // Encrypt & persist lanToken in Android Keystore!
                        lanCredentialStore.saveLanPairing(
                            lanToken = issuedToken,
                            bridgeDeviceId = bridgeId,
                            bridgeDeviceName = bridgeName,
                            connectedIp = endpoint.host,
                            connectedPort = endpoint.port,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            socketStatus = SocketStatus.CONNECTED,
                            paired = true,
                            desktopOnline = true,
                            lanConnectedHost = "${endpoint.host}:${endpoint.port}",
                            lanKnownIp = endpoint.host,
                            lanPairedDeviceName = bridgeName,
                            lanDiscoveredDeviceName = bridgeName,
                            lanDiscoveryFailed = false,
                            lanServerOffSuspected = false,
                            pairingBusy = false,
                            pairingError = null,
                            pairingSuccessHint = true,
                            lastError = null,
                            modeSwitchingHint = null,
                        )
                    }
                    startPingLoop()
                    reportClientModeToBridge()
                } else {
                    val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    val errMsg = payload["message"]?.jsonPrimitive?.contentOrNull ?: "内网配对失败，请核对桌面端 6 位配对码"
                    _uiState.update {
                        it.copy(
                            socketStatus = if (code == "rate_limited") SocketStatus.ERROR else SocketStatus.CONNECTED,
                            pairingBusy = false,
                            pairingError = errMsg,
                            pairingSuccessHint = false,
                            lastError = if (code == "rate_limited") errMsg else it.lastError,
                            modeSwitchingHint = null,
                        )
                    }
                    if (code != "rate_limited") {
                        // Close temporary pairing socket cleanly so next code attempt opens fresh first-frame pair.request
                        val ws = activeSocket
                        activeSocket = null
                        runCatching { ws?.close(1000, "retry_pair_code") }
                    }
                }
            }

            "pair.unpaired" -> {
                lanCredentialStore.clearLanPairing(keepKnownIp = true)
                _uiState.update {
                    it.copy(
                        paired = false,
                        desktopOnline = false,
                        pairingSuccessHint = false,
                        lanPairedDeviceName = "",
                    )
                }
            }

            "pong" -> {
                val clientTs = payload["clientTs"]?.jsonPrimitive?.longOrNull
                val rtt = if (clientTs != null) (System.currentTimeMillis() - clientTs).coerceAtLeast(1L) else null
                _uiState.update { cur ->
                    cur.copy(
                        latencyMs = rtt ?: cur.latencyMs,
                        desktopOnline = cur.paired,
                        networkTypeLabel = detectNetworkLabel()
                    )
                }
            }

            "protocol.error" -> {
                val msg = payload["message"]?.jsonPrimitive?.contentOrNull ?: "协议错误"
                _uiState.update { it.copy(protocolError = msg, lastError = msg) }
            }

            else -> {
                _incomingFrames.tryEmit(RelayFrame(type, reqId, ts, payload))
            }
        }
    }

    private fun acquireBriefWakeLock() {
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawBridge:LanRxWake")
                wl.setReferenceCounted(false)
                wl.acquire(4_000L)
            }
        }
    }

    private fun detectNetworkLabel(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "局域网"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 局域网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线局域网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络（内网直连需连同一 WiFi）"
            else -> "局域网"
        }
    }
}
