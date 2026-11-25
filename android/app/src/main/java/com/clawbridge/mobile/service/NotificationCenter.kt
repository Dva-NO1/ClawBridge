package com.clawbridge.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clawbridge.mobile.ClawBridgeApp
import com.clawbridge.mobile.MainActivity
import com.clawbridge.mobile.R
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.SettingsStore
import com.clawbridge.mobile.net.RelayFrame
import com.clawbridge.mobile.net.RelaySocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lockscreen-visible High-Priority Notification Engine for M3 / M4.
 *
 * Features:
 *   - Subscribes to `event.notify` on a single ordered coroutine so offline FIFO bursts
 *     (#1 -> #2 -> #3) post in strict arrival order without overwriting each other.
 *   - Deduplicates `notifyId` across reconnects.
 *   - Tapping any notification deep-links directly into the target session.
 *   - `approval.pending` notifications attach "批准 / 拒绝" action buttons:
 *       - `riskLevel == "low"`: "批准" executes directly via BroadcastReceiver; "拒绝" opens App
 *       - `riskLevel == "high"`: BOTH actions open App requiring PIN verification!
 */
class NotificationCenter(
    context: Context,
    private val settingsStore: SettingsStore,
    private val socketClient: RelaySocketClient,
) {
    private val appContext = context.applicationContext
    // Single-threaded ordered dispatcher guarantees strict FIFO processing of burst notifications
    private val orderedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val prefs = appContext.getSharedPreferences("clawbridge_notif_dedup", Context.MODE_PRIVATE)
    private val notifIdGen = AtomicInteger(3000)
    private var lastPostAtMs = 0L

    init {
        ensureAlertChannel(appContext)
        orderedScope.launch {
            socketClient.incomingFrames.collect { frame ->
                if (frame.type == "event.notify") {
                    processEventNotify(frame)
                }
            }
        }
    }

    private suspend fun processEventNotify(frame: RelayFrame) {
        val p: JsonObject = frame.payload
        val notifyId = p["notifyId"]?.jsonPrimitive?.contentOrNull ?: frame.reqId
        if (hasSeen(notifyId)) return
        markSeen(notifyId)

        val settings = settingsStore.settingsFlow.first()
        if (!settings.notificationsEnabled) return

        val category = p["category"]?.jsonPrimitive?.contentOrNull ?: "event.notify"
        val title = p["title"]?.jsonPrimitive?.contentOrNull ?: defaultTitleFor(category)
        val body = p["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: "antigravity"
        val riskLevel = p["riskLevel"]?.jsonPrimitive?.contentOrNull ?: "high"
        val approvalId = p["approvalId"]?.jsonPrimitive?.contentOrNull ?: ""
        val seq = p["seq"]?.jsonPrimitive?.intOrNull ?: notifIdGen.incrementAndGet()
        val eventTs = p["ts"]?.jsonPrimitive?.longOrNull ?: frame.ts

        // Slight 140ms spacing during rapid offline queue flush so HyperOS/Android renders
        // every notification banner & stack entry cleanly in exact chronological order!
        val now = System.currentTimeMillis()
        if (now - lastPostAtMs < 140L) {
            delay(140L - (now - lastPostAtMs))
        }
        lastPostAtMs = System.currentTimeMillis()

        val androidNotifId = 3000 + (Math.abs(notifyId.hashCode()) % 50000)
        val silentForDnd = isInDndWindow(settings)

        val openSessionPi = buildOpenAppIntent(
            ctx = appContext,
            reqCode = androidNotifId,
            sessionId = sessionId,
            source = source,
            approvalId = approvalId.takeIf { it.isNotBlank() },
            riskLevel = riskLevel,
            requestedAction = null
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALERTS_V2)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(body.ifBlank { title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { title }))
            .setWhen(eventTs)
            .setShowWhen(true)
            .setSortKey(String.format("%010d", 999999999 - (seq.coerceIn(0, 999999998))))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(
                if (category == "approval.pending") NotificationCompat.CATEGORY_CALL
                else NotificationCompat.CATEGORY_MESSAGE
            )
            .setDefaults(if (silentForDnd) 0 else NotificationCompat.DEFAULT_ALL)
            .setSilent(silentForDnd)
            .setAutoCancel(true)
            .setContentIntent(openSessionPi)

        if (category == "approval.pending") {
            if (riskLevel == "low" && approvalId.isNotBlank()) {
                // Spec 3.3 & 5.3: Low-risk approval can be approved directly from notification bar;
                // Reject must enter App (PIN check).
                val quickApproveIntent = Intent(appContext, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_QUICK_APPROVE_LOW
                    putExtra(NotificationActionReceiver.EXTRA_APPROVAL_ID, approvalId)
                    putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, androidNotifId)
                }
                val approvePi = PendingIntent.getBroadcast(
                    appContext,
                    androidNotifId + 1,
                    quickApproveIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val rejectOpenPi = buildOpenAppIntent(
                    ctx = appContext,
                    reqCode = androidNotifId + 2,
                    sessionId = sessionId,
                    source = source,
                    approvalId = approvalId,
                    riskLevel = riskLevel,
                    requestedAction = "reject"
                )

                builder.addAction(0, "批准", approvePi)
                builder.addAction(0, "拒绝(进App)", rejectOpenPi)
            } else {
                // Spec 3.3 & 7: High-risk approval MUST enter App and verify PIN!
                val highApprovePi = buildOpenAppIntent(
                    ctx = appContext,
                    reqCode = androidNotifId + 1,
                    sessionId = sessionId,
                    source = source,
                    approvalId = approvalId,
                    riskLevel = "high",
                    requestedAction = "approve_pin"
                )
                val highRejectPi = buildOpenAppIntent(
                    ctx = appContext,
                    reqCode = androidNotifId + 2,
                    sessionId = sessionId,
                    source = source,
                    approvalId = approvalId,
                    riskLevel = "high",
                    requestedAction = "reject"
                )
                builder.addAction(0, "进App输PIN批准", highApprovePi)
                builder.addAction(0, "进App拒绝", highRejectPi)
            }
        }

        runCatching {
            NotificationManagerCompat.from(appContext).notify(androidNotifId, builder.build())
        }
    }

    private fun defaultTitleFor(category: String): String = when (category) {
        "approval.pending" -> "⏳ 待确认操作"
        "task.done" -> "✅ 任务已完成"
        "task.error" -> "❌ 任务执行报错"
        else -> "ClawBridge 提醒"
    }

    private fun isInDndWindow(s: AppSettings): Boolean {
        if (!s.dndEnabled) return false
        val startMin = parseHhMm(s.dndStart) ?: return false
        val endMin = parseHhMm(s.dndEnd) ?: return false
        val cal = Calendar.getInstance()
        val curMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMin <= endMin) {
            curMin in startMin..endMin
        } else {
            // Cross-midnight DND (e.g. 23:30 -> 07:00)
            curMin >= startMin || curMin <= endMin
        }
    }

    private fun parseHhMm(str: String): Int? {
        val parts = str.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    @Synchronized
    private fun hasSeen(id: String): Boolean {
        if (id.isBlank()) return false
        val raw = prefs.getString("seen_ids", "") ?: ""
        return raw.split("|").contains(id)
    }

    @Synchronized
    private fun markSeen(id: String) {
        if (id.isBlank()) return
        val existing = (prefs.getString("seen_ids", "") ?: "")
            .split("|")
            .filter { it.isNotBlank() }
            .toMutableList()
        existing.add(id)
        val trimmed = if (existing.size > 300) existing.takeLast(300) else existing
        prefs.edit().putString("seen_ids", trimmed.joinToString("|")).apply()
    }

    companion object {
        const val CHANNEL_ALERTS_V2 = "clawbridge_alerts_v2"

        fun ensureAlertChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ALERTS_V2,
                "Agent 锁屏告警与审批推送",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "待确认审批、任务完成、异常报错及早晚助理推送（锁屏可见）"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 260, 120, 260)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }

        private fun buildOpenAppIntent(
            ctx: Context,
            reqCode: Int,
            sessionId: String,
            source: String,
            approvalId: String?,
            riskLevel: String?,
            requestedAction: String?
        ): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                if (sessionId.isNotBlank()) putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
                putExtra(MainActivity.EXTRA_OPEN_SESSION_SOURCE, source)
                if (!approvalId.isNullOrBlank()) putExtra(MainActivity.EXTRA_APPROVAL_ID, approvalId)
                if (!riskLevel.isNullOrBlank()) putExtra(MainActivity.EXTRA_APPROVAL_RISK, riskLevel)
                if (!requestedAction.isNullOrBlank()) putExtra(MainActivity.EXTRA_APPROVAL_ACTION, requestedAction)
            }
            return PendingIntent.getActivity(
                ctx,
                reqCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_QUICK_APPROVE_LOW) return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val app = context.applicationContext as? ClawBridgeApp ?: return
        app.socketClient.sendEnvelope(
            "approval.respond",
            buildJsonObject {
                put("approvalId", JsonPrimitive(approvalId))
                put("sessionId", JsonPrimitive(sessionId))
                put("decision", JsonPrimitive("approve"))
                put("pinVerified", JsonPrimitive(false))
            }
        )

        if (notifId > 0) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
    }

    companion object {
        const val ACTION_QUICK_APPROVE_LOW = "com.clawbridge.mobile.ACTION_QUICK_APPROVE_LOW"
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
