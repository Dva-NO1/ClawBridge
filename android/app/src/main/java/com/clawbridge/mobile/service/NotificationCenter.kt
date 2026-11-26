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
import com.clawbridge.mobile.data.MonitorRepository
import com.clawbridge.mobile.data.SessionDetailState
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.data.SettingsStore
import com.clawbridge.mobile.net.ConnectionTransport
import com.clawbridge.mobile.net.RelayFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val socketClient: ConnectionTransport,
    private val monitorRepository: MonitorRepository? = null,
) {
    private val appContext = context.applicationContext
    // Single-threaded ordered dispatcher guarantees strict FIFO processing of burst notifications
    private val orderedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val prefs = appContext.getSharedPreferences("clawbridge_notif_dedup", Context.MODE_PRIVATE)
    private val notifIdGen = AtomicInteger(3000)
    private var lastPostAtMs = 0L

    // Cross-source per-session completion deduplication (prevents double-ping when both event.notify & local stream transition arrive)
    private val recentTaskDoneBySession = ConcurrentHashMap<String, Long>()
    // Local session state tracker: key "${source}:${id}" -> last observed status
    private val observedSessionStatus = ConcurrentHashMap<String, String>()
    private val pendingLocalDoneJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var localSessionsSeeded = false

    init {
        ensureAlertChannel(appContext)
        orderedScope.launch {
            socketClient.incomingFrames.collect { frame ->
                if (frame.type == "event.notify") {
                    processEventNotify(frame)
                }
            }
        }
        if (monitorRepository != null) {
            orderedScope.launch {
                monitorRepository.sessions.collect { list ->
                    observeSessionListTransitions(list)
                }
            }
            orderedScope.launch {
                monitorRepository.activeDetail.collect { detail ->
                    if (detail != null) {
                        observeActiveDetailTransition(detail)
                    }
                }
            }
        }
    }

    private fun isBusyStatus(status: String): Boolean =
        status == "running" || status == "waiting_approval"

    private fun observeSessionListTransitions(list: List<SessionSummaryUi>) {
        if (list.isEmpty()) return
        if (!localSessionsSeeded) {
            for (s in list) {
                observedSessionStatus["${s.source}:${s.id}"] = s.status
            }
            localSessionsSeeded = true
            return
        }

        for (s in list) {
            val key = "${s.source}:${s.id}"
            val prev = observedSessionStatus.put(key, s.status)
            if (isBusyStatus(s.status)) {
                recentTaskDoneBySession.remove(key)
                pendingLocalDoneJobs.remove(key)?.cancel()
            } else if (prev != null && isBusyStatus(prev) && s.status == "done") {
                scheduleLocalTaskDoneNotification(s, fallbackSummary = s.preview)
            }
        }
    }

    private fun observeActiveDetailTransition(detail: SessionDetailState) {
        if (detail.loading || detail.fromCache) return
        val s = detail.session
        val key = "${s.source}:${s.id}"
        val lastMsg = detail.messages.lastOrNull()
        val effectivelyRunning = isBusyStatus(s.status) ||
            lastMsg?.status == "streaming" ||
            (lastMsg?.role == "user" && lastMsg.id.startsWith("local-"))

        val prev = observedSessionStatus[key]
        if (effectivelyRunning) {
            observedSessionStatus[key] = "running"
            recentTaskDoneBySession.remove(key)
            pendingLocalDoneJobs.remove(key)?.cancel()
        } else if (prev != null && isBusyStatus(prev) && s.status == "done") {
            observedSessionStatus[key] = "done"
            val latestAgentReply = detail.messages.lastOrNull { it.role == "agent" && it.text.isNotBlank() }?.text
            scheduleLocalTaskDoneNotification(s, fallbackSummary = latestAgentReply ?: s.preview)
        }
    }

    private fun scheduleLocalTaskDoneNotification(session: SessionSummaryUi, fallbackSummary: String) {
        val key = "${session.source}:${session.id}"
        pendingLocalDoneJobs.remove(key)?.cancel()
        pendingLocalDoneJobs[key] = orderedScope.launch {
            delay(1_800L)
            pendingLocalDoneJobs.remove(key)
            if (observedSessionStatus[key] != "done") return@launch

            val activeMatch = monitorRepository?.activeDetail?.value?.takeIf { it.session.id == session.id }
            val latestAgentText = activeMatch?.messages
                ?.lastOrNull { it.role == "agent" && it.text.isNotBlank() }
                ?.text
                ?: fallbackSummary
            val cleanSummary = latestAgentText
                .replace(Regex("```[\\s\\S]*?```"), "[代码块]")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(110)
                .ifBlank { "会话本轮任务已执行完毕，点击查看详情" }

            val badge = if (session.source == "codex") "Codex" else "AGY"
            val proj = session.project.ifBlank { badge }
            val syntheticFrame = RelayFrame(
                type = "event.notify",
                reqId = UUID.randomUUID().toString(),
                ts = System.currentTimeMillis(),
                payload = buildJsonObject {
                    put("notifyId", JsonPrimitive("local-done-$key-${System.currentTimeMillis()}"))
                    put("category", JsonPrimitive("task.done"))
                    put("title", JsonPrimitive("✅ 任务已完成 · $proj ($badge)"))
                    put("body", JsonPrimitive("「${session.title}」：$cleanSummary"))
                    put("sessionId", JsonPrimitive(session.id))
                    put("source", JsonPrimitive(session.source))
                    put("project", JsonPrimitive(proj))
                    put("ts", JsonPrimitive(System.currentTimeMillis()))
                }
            )
            processEventNotify(syntheticFrame)
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
        if (category == "task.done" && !settings.taskDoneNotifyEnabled) return

        val title = p["title"]?.jsonPrimitive?.contentOrNull ?: defaultTitleFor(category)
        val body = p["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: "antigravity"

        if (category == "task.done" && sessionId.isNotBlank()) {
            val sessKey = "${source.lowercase()}:$sessionId"
            val nowCheck = System.currentTimeMillis()
            val prevAt = recentTaskDoneBySession[sessKey] ?: 0L
            if (nowCheck - prevAt < 15_000L) {
                return
            }
            recentTaskDoneBySession[sessKey] = nowCheck
        }
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
