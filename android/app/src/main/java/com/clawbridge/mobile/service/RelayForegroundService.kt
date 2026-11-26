package com.clawbridge.mobile.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clawbridge.mobile.ClawBridgeApp
import com.clawbridge.mobile.MainActivity
import com.clawbridge.mobile.R
import com.clawbridge.mobile.net.ConnectionMode
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class RelayForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        NotificationCenter.ensureAlertChannel(this)

        val app = application as ClawBridgeApp
        val initial = buildStatusNotification(app.socketClient.uiState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID_FOREGROUND, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID_FOREGROUND, initial)
        }

        acquireWifiKeepaliveLock()
        KeepalivePulseReceiver.scheduleNext(this)

        app.socketClient.start()

        app.socketClient.uiState
            .onEach { state ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID_FOREGROUND, buildStatusNotification(state))
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KeepalivePulseReceiver.scheduleNext(this)
        (application as ClawBridgeApp).socketClient.start()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Re-schedule pulse if user swipes task from recents on HyperOS/MIUI
        KeepalivePulseReceiver.scheduleNext(this, delayMs = 5_000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWifiKeepaliveLock()
        serviceScope.cancel()
        KeepalivePulseReceiver.scheduleNext(this, delayMs = 5_000L)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWifiKeepaliveLock() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "ClawBridge:WifiKeepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiKeepaliveLock() {
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    private fun buildStatusNotification(state: ConnectionUiState): Notification {
        val isLan = state.connectionMode == ConnectionMode.LAN
        val statusText = if (isLan) {
            when {
                state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline -> {
                    val rtt = state.latencyMs?.let { " · ${it}ms" } ?: ""
                    val host = state.lanConnectedHost.ifBlank { "局域网" }
                    "内网直连在线$rtt ($host)"
                }
                state.socketStatus == SocketStatus.CONNECTED && !state.paired ->
                    "内网已发现电脑 · 等待输入 6 位内网配对码"
                state.socketStatus == SocketStatus.CONNECTING ->
                    "正在发现并直连局域网电脑..."
                else ->
                    "内网未连接 · 请确认电脑端已开启内网模式"
            }
        } else {
            when {
                state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline -> {
                    val rtt = state.latencyMs?.let { " · ${it}ms" } ?: ""
                    "桌面在线$rtt (${state.networkTypeLabel})"
                }
                state.socketStatus == SocketStatus.CONNECTED && state.paired && !state.desktopOnline ->
                    "中继已连 · 桌面桥离线"
                state.socketStatus == SocketStatus.CONNECTED && !state.paired ->
                    "中继已连 · 等待输入配对码"
                state.socketStatus == SocketStatus.CONNECTING ->
                    "正在连接中继..."
                else ->
                    "连接中断，后台自动重连中..."
            }
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("ClawBridge 远程守护")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fgChannel = NotificationChannel(
            CHANNEL_FOREGROUND,
            "常驻连接守护状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与中继服务器的实时锁屏推送连接"
            setShowBadge(false)
        }
        nm.createNotificationChannel(fgChannel)
    }

    companion object {
        const val CHANNEL_FOREGROUND = "clawbridge_fg_status"
        private const val NOTIF_ID_FOREGROUND = 1001

        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}

/**
 * Doze-proof AllowWhileIdle heartbeat receiver for Xiaomi HyperOS / domestic ROMs.
 * Wakes every 3.5 minutes during 30+ minute screen-off periods to verify WSS liveness.
 */
class KeepalivePulseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawBridge:DozePulse")?.apply {
            setReferenceCounted(false)
            runCatching { acquire(8_000L) }
        }

        scheduleNext(context)
        RelayForegroundService.start(context)

        val app = context.applicationContext as? ClawBridgeApp
        app?.socketClient?.pulseFromDoze()
    }

    companion object {
        private const val PULSE_INTERVAL_MS = 210_000L // 3.5 minutes

        fun scheduleNext(context: Context, delayMs: Long = PULSE_INTERVAL_MS) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, KeepalivePulseReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context,
                    1901,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            RelayForegroundService.start(context)
            KeepalivePulseReceiver.scheduleNext(context)
        }
    }
}
