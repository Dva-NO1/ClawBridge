package com.clawbridge.mobile.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.clawbridge.mobile.data.AppSettings
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WAN Relay WebSocket Transport (`wss://...` via Relay server).
 * Preserves 100% of existing WAN Relay connection, pairing, heartbeat, and TLS security behavior.
 */
class RelayTransport(
    context: Context,
    private val settingsStore: SettingsStore,
) : ConnectionTransport {

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val APP_PING_INTERVAL_MS = 25_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ConnectionUiState(connectionMode = ConnectionMode.WAN))
    override val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayFrame>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingFrames: SharedFlow<RelayFrame> = _incomingFrames.asSharedFlow()

    private var connectionLoopJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var lastRxAtMs: Long = 0L
    @Volatile private var retryTrigger = CompletableDeferred<Unit>()
    @Volatile private var running = false

    override fun start() {
        running = true
        if (connectionLoopJob?.isActive == true) return
        connectionLoopJob = scope.launch {
            settingsStore.ensureDeviceId()
            runReconnectLoop()
        }
    }

    override fun stop() {
        running = false
        pingJob?.cancel()
        pingJob = null
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        val old = activeSocket
        activeSocket = null
        runCatching { old?.close(1000, "switched_to_lan") }
        _uiState.update {
            it.copy(
                socketStatus = SocketStatus.DISCONNECTED,
                desktopOnline = false,
                pairingBusy = false,
            )
        }
    }

    override fun reconnectNow() {
        if (!running) return
        val oldSocket = activeSocket
        activeSocket = null
        oldSocket?.cancel()
        if (!retryTrigger.isCompleted) {
            retryTrigger.complete(Unit)
        }
    }

    override fun sendPingNow() {
        sendEnvelope("ping", buildJsonObject { })
    }

    override fun pulseFromDoze() {
        if (!running) return
        start()
        val ws = activeSocket
        if (ws == null || _uiState.value.socketStatus != SocketStatus.CONNECTED) {
            reconnectNow()
            return
        }
        val beforeRx = lastRxAtMs
        val sent = sendEnvelope("ping", buildJsonObject { })
        if (!sent) {
            reconnectNow()
            return
        }
        scope.launch {
            delay(6_000L)
            if (running && lastRxAtMs <= beforeRx) {
                reconnectNow()
            }
        }
    }

    override fun submitPairingCode(code: String) {
        val trimmed = code.trim()
        if (!trimmed.matches(Regex("^\\d{6}$"))) {
            _uiState.update { it.copy(pairingError = "请输入 6 位数字配对码", pairingBusy = false) }
            return
        }
        if (activeSocket == null || _uiState.value.socketStatus != SocketStatus.CONNECTED) {
            _uiState.update { it.copy(pairingError = "尚未连上中继服务器，正在重连...", pairingBusy = false) }
            reconnectNow()
            return
        }

        _uiState.update { it.copy(pairingBusy = true, pairingError = null, pairingSuccessHint = false) }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        sendEnvelope(
            "pair.request",
            buildJsonObject {
                put("code", JsonPrimitive(trimmed))
                put("deviceName", JsonPrimitive(deviceName))
            }
        )
    }

    override fun unpairAndReset() {
        scope.launch {
            sendEnvelope("pair.unpair", buildJsonObject { })
            settingsStore.clearPairing()
            _uiState.update {
                it.copy(
                    paired = false,
                    desktopOnline = false,
                    pairingError = null,
                    pairingSuccessHint = false
                )
            }
            if (running) reconnectNow()
        }
    }

    fun applyNewRelayConfig(newUrl: String, trustSelfSigned: Boolean) {
        scope.launch {
            val changed = settingsStore.updateRelayConfig(newUrl, trustSelfSigned)
            _uiState.update {
                it.copy(
                    relayUrl = SettingsStore.normalizeRelayUrl(newUrl),
                    paired = if (changed) false else it.paired,
                    desktopOnline = if (changed) false else it.desktopOnline,
                    latencyMs = null,
                    lastError = null,
                    protocolError = null,
                    pairingError = null,
                    pairingSuccessHint = false,
                )
            }
            if (running) reconnectNow()
        }
    }

    fun notifyNetworkChanged() {
        _uiState.update { it.copy(networkTypeLabel = detectNetworkLabel()) }
        if (running) reconnectNow()
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
                put("mode", JsonPrimitive(ConnectionMode.WAN.wireValue))
            }
        )
    }

    private suspend fun runReconnectLoop() {
        var backoffMs = 1_000L
        while (running) {
            val cfg = settingsStore.settingsFlow.first()
            val deviceId = settingsStore.ensureDeviceId()

            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.WAN,
                    socketStatus = SocketStatus.CONNECTING,
                    relayUrl = cfg.relayUrl,
                    paired = cfg.isPaired,
                    networkTypeLabel = detectNetworkLabel(),
                    modeSwitchingHint = null,
                )
            }

            val opened = openSessionOnce(cfg.copy(deviceId = deviceId))
            if (!running) break
            if (opened) {
                backoffMs = 1_000L
            } else {
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }

            retryTrigger = CompletableDeferred()
            val waitJob = scope.launch { delay(backoffMs) }
            kotlinx.coroutines.selects.select<Unit> {
                waitJob.onJoin { }
                retryTrigger.onAwait { waitJob.cancel() }
            }
            if (retryTrigger.isCompleted) {
                backoffMs = 1_000L
            }
        }
    }

    private suspend fun openSessionOnce(cfg: AppSettings): Boolean {
        if (!cfg.isRelayConfigured) {
            _uiState.update {
                it.copy(
                    socketStatus = SocketStatus.DISCONNECTED,
                    lastError = "外网模式需先前往「设置」填写自建 VPS 中继地址（同一 WiFi 下可直接点击顶部「内网直连」免服务器使用）"
                )
            }
            return false
        }
        // Hard security requirement: WAN Relay must use TLS; block plaintext ws:// except localhost
        val lower = cfg.relayUrl.lowercase()
        if (lower.startsWith("ws://") && !lower.contains("127.0.0.1") && !lower.contains("localhost")) {
            _uiState.update {
                it.copy(
                    socketStatus = SocketStatus.ERROR,
                    lastError = "安全策略拒绝外网明文 ws:// 连接，请使用 wss://"
                )
            }
            return false
        }

        var everAuthenticated = false
        val closedGate = CompletableDeferred<Unit>()
        val client = buildHttpClient(cfg.trustSelfSigned)

        val req = try {
            Request.Builder().url(cfg.relayUrl).build()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    socketStatus = SocketStatus.ERROR,
                    lastError = "中继地址格式无效: ${e.message}"
                )
            }
            return false
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!running) {
                    webSocket.close(1000, "stopped")
                    if (!closedGate.isCompleted) closedGate.complete(Unit)
                    return
                }
                activeSocket = webSocket
                _uiState.update {
                    it.copy(
                        socketStatus = SocketStatus.CONNECTED,
                        lastError = null,
                        networkTypeLabel = detectNetworkLabel()
                    )
                }

                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                sendEnvelope(
                    "hello",
                    buildJsonObject {
                        put("role", JsonPrimitive("phone"))
                        put("deviceId", JsonPrimitive(cfg.deviceId))
                        put("deviceToken", JsonPrimitive(cfg.deviceToken))
                        put("deviceName", JsonPrimitive(deviceName))
                        put("appVersion", JsonPrimitive("2.0.0"))
                    }
                )
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRxAtMs = System.currentTimeMillis()
                acquireBriefWakeLock()
                if (handleIncomingText(text)) {
                    everAuthenticated = true
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cleanupSocket(webSocket, closedGate, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = when {
                    t is javax.net.ssl.SSLHandshakeException && !cfg.trustSelfSigned ->
                        "TLS 证书校验失败（已按安全策略拒绝不受信任/自签证书）"
                    else -> t.message ?: "网络连接中断"
                }
                cleanupSocket(webSocket, closedGate, msg)
            }
        }

        activeSocket = client.newWebSocket(req, listener)
        closedGate.await()
        return everAuthenticated
    }

    private fun cleanupSocket(ws: WebSocket, gate: CompletableDeferred<Unit>, err: String?) {
        if (activeSocket === ws) {
            activeSocket = null
        }
        pingJob?.cancel()
        pingJob = null
        if (running) {
            _uiState.update {
                it.copy(
                    socketStatus = if (err != null) SocketStatus.ERROR else SocketStatus.DISCONNECTED,
                    desktopOnline = false,
                    pairingBusy = false,
                    lastError = err ?: it.lastError,
                    networkTypeLabel = detectNetworkLabel()
                )
            }
        }
        if (!gate.isCompleted) gate.complete(Unit)
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(400)
            sendEnvelope("ping", buildJsonObject { })
            while (running && activeSocket != null) {
                delay(APP_PING_INTERVAL_MS)
                sendEnvelope("ping", buildJsonObject { })
            }
        }
    }

    private fun handleIncomingText(text: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val version = obj["v"]?.jsonPrimitive?.intOrNull
        val reqId = obj["reqId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        if (version != PROTOCOL_VERSION) {
            val msg = "协议版本不匹配：手机端 v=$PROTOCOL_VERSION，收到 v=${version ?: "null"}"
            _uiState.update { it.copy(protocolError = msg, lastError = msg) }
            sendEnvelope(
                "protocol.error",
                buildJsonObject {
                    put("code", JsonPrimitive("version_mismatch"))
                    put("message", JsonPrimitive(msg))
                    put("expectedVersion", JsonPrimitive(PROTOCOL_VERSION))
                },
                reqId
            )
            return false
        }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return false
        val payload = (obj["payload"] as? JsonObject) ?: buildJsonObject { }

        when (type) {
            "pong" -> {
                val clientTs = payload["clientTs"]?.jsonPrimitive?.longOrNull
                val peerOnline = payload["peerOnline"]?.jsonPrimitive?.booleanOrNull
                val rtt = if (clientTs != null) (System.currentTimeMillis() - clientTs).coerceAtLeast(1L) else null
                _uiState.update { cur ->
                    cur.copy(
                        latencyMs = rtt ?: cur.latencyMs,
                        desktopOnline = if (cur.paired && peerOnline != null) peerOnline else cur.desktopOnline,
                        networkTypeLabel = detectNetworkLabel()
                    )
                }
                return true
            }

            "hello.ack" -> {
                val paired = payload["paired"]?.jsonPrimitive?.booleanOrNull ?: false
                val peerOnline = payload["peerOnline"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!paired) {
                    scope.launch { settingsStore.clearPairing() }
                }
                _uiState.update {
                    it.copy(
                        paired = paired,
                        desktopOnline = paired && peerOnline,
                        protocolError = null,
                        lastError = null
                    )
                }
                sendPingNow()
                if (paired && peerOnline) {
                    reportClientModeToBridge()
                }
                return true
            }

            "pair.result" -> {
                val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (success) {
                    val token = payload["deviceToken"]?.jsonPrimitive?.contentOrNull ?: ""
                    val bridgeId = payload["bridgeDeviceId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val bridgeOnline = payload["bridgeOnline"]?.jsonPrimitive?.booleanOrNull ?: true
                    scope.launch {
                        settingsStore.savePairingCredentials(token, bridgeId)
                    }
                    _uiState.update {
                        it.copy(
                            paired = true,
                            desktopOnline = bridgeOnline,
                            pairingBusy = false,
                            pairingError = null,
                            pairingSuccessHint = true
                        )
                    }
                    sendPingNow()
                    if (bridgeOnline) {
                        reportClientModeToBridge()
                    }
                } else {
                    val errMsg = payload["message"]?.jsonPrimitive?.contentOrNull ?: "配对失败，请检查配对码"
                    _uiState.update {
                        it.copy(
                            pairingBusy = false,
                            pairingError = errMsg,
                            pairingSuccessHint = false
                        )
                    }
                }
                return success
            }

            "pair.unpaired" -> {
                scope.launch { settingsStore.clearPairing() }
                _uiState.update {
                    it.copy(
                        paired = false,
                        desktopOnline = false,
                        pairingSuccessHint = false
                    )
                }
                return false
            }

            "presence" -> {
                val online = payload["online"]?.jsonPrimitive?.booleanOrNull ?: false
                _uiState.update {
                    it.copy(
                        desktopOnline = online,
                        networkTypeLabel = detectNetworkLabel()
                    )
                }
                if (online) {
                    reportClientModeToBridge()
                }
                return true
            }

            "desktop_offline" -> {
                _uiState.update { it.copy(desktopOnline = false) }
                _incomingFrames.tryEmit(RelayFrame(type, reqId, ts, payload))
                return true
            }

            "protocol.error" -> {
                val msg = payload["message"]?.jsonPrimitive?.contentOrNull ?: "协议错误"
                _uiState.update { it.copy(protocolError = msg, lastError = msg) }
                return false
            }

            else -> {
                _incomingFrames.tryEmit(RelayFrame(type, reqId, ts, payload))
                return true
            }
        }
    }

    private fun acquireBriefWakeLock() {
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawBridge:RxWake")
                wl.setReferenceCounted(false)
                wl.acquire(4_000L)
            }
        }
    }

    private fun detectNetworkLabel(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "未知网络"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝移动网 (5G/4G)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            else -> "在线网络"
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun buildHttpClient(trustSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(DoHDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        if (trustSelfSigned) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
