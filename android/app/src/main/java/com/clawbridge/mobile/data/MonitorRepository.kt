package com.clawbridge.mobile.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import com.clawbridge.mobile.net.RelayFrame
import com.clawbridge.mobile.net.RelaySocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MonitorRepository(
    context: Context,
    private val socketClient: RelaySocketClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _quota = MutableStateFlow(QuotaDashboardState())
    val quota: StateFlow<QuotaDashboardState> = _quota.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummaryUi>>(emptyList())
    val sessions: StateFlow<List<SessionSummaryUi>> = _sessions.asStateFlow()

    private val _cdpNote = MutableStateFlow("")
    val cdpNote: StateFlow<String> = _cdpNote.asStateFlow()

    private val _antigravityModels = MutableStateFlow<List<ModelOptionUi>>(emptyList())
    private val _codexModels = MutableStateFlow<List<ModelOptionUi>>(emptyList())

    // Two-layer LRU + disk cache for instant session open while live snapshot syncs
    private val detailCache = SessionDetailCache(appContext, maxEntries = 20)
    private val metricsCache = ConcurrentHashMap<String, SessionMetricsState>()

    private val _activeDetail = MutableStateFlow<SessionDetailState?>(null)
    val activeDetail: StateFlow<SessionDetailState?> = _activeDetail.asStateFlow()

    private val _activeMetrics = MutableStateFlow<SessionMetricsState?>(null)
    val activeMetrics: StateFlow<SessionMetricsState?> = _activeMetrics.asStateFlow()

    private val _preview = MutableStateFlow<ArtifactPreviewState?>(null)
    val preview: StateFlow<ArtifactPreviewState?> = _preview.asStateFlow()

    private val _downloadStatus = MutableStateFlow(DownloadStatusState())
    val downloadStatus: StateFlow<DownloadStatusState> = _downloadStatus.asStateFlow()

    private val _uploadStatus = MutableStateFlow(UploadStatusState())
    val uploadStatus: StateFlow<UploadStatusState> = _uploadStatus.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApprovalUi>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApprovalUi>> = _pendingApprovals.asStateFlow()

    private val _actionFeedback = MutableStateFlow(ActionFeedbackState())
    val actionFeedback: StateFlow<ActionFeedbackState> = _actionFeedback.asStateFlow()

    // Triggered when user taps "进App输PIN批准" or "拒绝(进App)" on a notification
    private val _deepLinkApprovalPrompt = MutableStateFlow<Triple<String, String, String>?>(null)
    val deepLinkApprovalPrompt: StateFlow<Triple<String, String, String>?> = _deepLinkApprovalPrompt.asStateFlow()

    private class ChunkAccumulator(
        val fileName: String,
        val mimeType: String,
        val totalChunks: Int,
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
    )

    private val activeTransfers = ConcurrentHashMap<String, ChunkAccumulator>()

    init {
        loadRecentSessionsFromDisk()

        socketClient.incomingFrames
            .onEach { frame -> handleFrame(frame) }
            .launchIn(scope)

        // Automatically request quota & session list whenever desktop comes online
        socketClient.uiState
            .map { it.paired && it.desktopOnline }
            .distinctUntilChanged()
            .onEach { online ->
                if (online) {
                    refreshQuota(force = false)
                    refreshSessions()
                    refreshModels(force = false)
                    refreshApprovals()
                    // Re-subscribe open session & fetch latest metrics snapshot if user is viewing one
                    _activeDetail.value?.session?.let { open ->
                        socketClient.sendEnvelope(
                            "session.open",
                            buildJsonObject {
                                put("sessionId", JsonPrimitive(open.id))
                                put("source", JsonPrimitive(open.source))
                            }
                        )
                        requestMetricsSnapshot(open.id, open.source)
                    }
                }
            }
            .launchIn(scope)
    }

    fun refreshQuota(force: Boolean = true) {
        socketClient.sendEnvelope(
            "quota.get",
            buildJsonObject {
                put("force", JsonPrimitive(force))
            }
        )
    }

    fun refreshSessions() {
        socketClient.sendEnvelope("session.list.get", buildJsonObject { })
    }

    fun refreshApprovals() {
        socketClient.sendEnvelope("approval.list.get", buildJsonObject { })
    }

    fun refreshModels(force: Boolean = false) {
        socketClient.sendEnvelope(
            "model.list.get",
            buildJsonObject {
                put("force", JsonPrimitive(force))
            }
        )
    }

    fun setSessionModel(sessionId: String, source: String, modelId: String, modelLabel: String) {
        if (sessionId.isBlank() || modelId.isBlank()) return
        _activeDetail.update { cur ->
            if (cur == null || cur.session.id != sessionId) cur
            else cur.copy(
                session = cur.session.copy(
                    currentModelId = modelId,
                    currentModelLabel = modelLabel
                )
            )
        }
        val sent = socketClient.sendEnvelope(
            "session.model.set",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("source", JsonPrimitive(source))
                put("modelId", JsonPrimitive(modelId))
                put("modelLabel", JsonPrimitive(modelLabel))
            }
        )
        if (!sent) {
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "桌面未连接，模型切换未保存", isError = true)
        }
    }

    fun triggerDeepLinkApprovalPrompt(approvalId: String, riskLevel: String, action: String) {
        _deepLinkApprovalPrompt.value = Triple(approvalId, riskLevel, action)
    }

    fun clearDeepLinkApprovalPrompt() {
        _deepLinkApprovalPrompt.value = null
    }

    fun clearActionFeedback() {
        _actionFeedback.value = ActionFeedbackState()
    }

    fun sendChatMessage(sessionId: String, source: String, text: String) {
        val trimmed = text.trim()
        if (sessionId.isBlank() || trimmed.isEmpty()) return

        // Optimistic user bubble in active session
        _activeDetail.update { cur ->
            if (cur == null || cur.session.id != sessionId) cur
            else {
                val optimistic = ChatMessageUi(
                    id = "local-${System.currentTimeMillis()}",
                    role = "user",
                    text = trimmed,
                    thinkingSummary = "",
                    toolSteps = emptyList(),
                    status = "done",
                    ts = System.currentTimeMillis()
                )
                cur.copy(messages = cur.messages + optimistic)
            }
        }

        val selectedModelId = _activeDetail.value
            ?.takeIf { it.session.id == sessionId }
            ?.session?.currentModelId
            ?.takeIf { it.isNotBlank() }

        _actionFeedback.value = ActionFeedbackState(sending = true)
        val sent = socketClient.sendEnvelope(
            "chat.send",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("source", JsonPrimitive(source))
                put("text", JsonPrimitive(trimmed))
                if (selectedModelId != null) {
                    put("modelId", JsonPrimitive(selectedModelId))
                }
            }
        )
        if (!sent) {
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "桌面未连接，消息未发送", isError = true)
        }
    }

    fun sendQuickCommand(sessionId: String, source: String, commandKey: String, displayLabel: String) {
        if (sessionId.isBlank()) return
        _activeDetail.update { cur ->
            if (cur == null || cur.session.id != sessionId) cur
            else {
                val optimistic = ChatMessageUi(
                    id = "quick-${System.currentTimeMillis()}",
                    role = "user",
                    text = "[$displayLabel]",
                    thinkingSummary = "",
                    toolSteps = emptyList(),
                    status = "done",
                    ts = System.currentTimeMillis()
                )
                cur.copy(messages = cur.messages + optimistic)
            }
        }
        val selectedModelId = _activeDetail.value
            ?.takeIf { it.session.id == sessionId }
            ?.session?.currentModelId
            ?.takeIf { it.isNotBlank() }

        _actionFeedback.value = ActionFeedbackState(sending = true)
        val sent = socketClient.sendEnvelope(
            "quick.command",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("source", JsonPrimitive(source))
                put("commandKey", JsonPrimitive(commandKey))
                if (selectedModelId != null) {
                    put("modelId", JsonPrimitive(selectedModelId))
                }
            }
        )
        if (!sent) {
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "桌面未连接，快捷指令未发送", isError = true)
        }
    }

    fun respondApproval(approvalId: String, sessionId: String, decision: String, pinVerified: Boolean) {
        _actionFeedback.value = ActionFeedbackState(sending = true)
        val sent = socketClient.sendEnvelope(
            "approval.respond",
            buildJsonObject {
                put("approvalId", JsonPrimitive(approvalId))
                put("sessionId", JsonPrimitive(sessionId))
                put("decision", JsonPrimitive(decision))
                put("pinVerified", JsonPrimitive(pinVerified))
            }
        )
        if (!sent) {
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "连接中断，审批发送失败", isError = true)
        }
    }

    fun requestMetricsSnapshot(sessionId: String, source: String) {
        if (sessionId.isBlank()) return
        socketClient.sendEnvelope(
            "metrics.get",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("source", JsonPrimitive(source))
            }
        )
    }

    fun openSession(summary: SessionSummaryUi) {
        val cachedModels = if (summary.source == "codex") _codexModels.value else _antigravityModels.value
        val mKey = "${summary.source.lowercase()}:${summary.id}"
        // Restore per-session cached metrics (never carry over previous session's metrics)
        _activeMetrics.value = metricsCache[mKey]

        // L1 in-memory instant hit -> 0ms render!
        val memCached = detailCache.getMemory(summary.source, summary.id)
        if (memCached != null) {
            val mergedSession = memCached.session.copy(
                title = summary.title.ifBlank { memCached.session.title },
                status = summary.status.ifBlank { memCached.session.status },
                updatedAt = maxOf(summary.updatedAt, memCached.session.updatedAt),
                currentModelId = memCached.session.currentModelId.ifBlank { summary.currentModelId },
                currentModelLabel = memCached.session.currentModelLabel.ifBlank { summary.currentModelLabel },
            )
            _activeDetail.value = memCached.copy(
                session = mergedSession,
                availableModels = cachedModels.ifEmpty { memCached.availableModels },
                loading = false,
                syncing = true,
                fromCache = true,
            )
        } else {
            _activeDetail.value = SessionDetailState(
                session = summary,
                availableModels = cachedModels,
                loading = true,
                syncing = true,
                fromCache = false,
            )
            // L2 disk cache check on IO dispatcher (arrives in ~2-8ms, far faster than WSS round-trip)
            scope.launch {
                val diskCached = detailCache.get(summary.source, summary.id)
                if (diskCached != null) {
                    _activeDetail.update { cur ->
                        if (cur == null || cur.session.id != summary.id || !cur.loading) cur
                        else {
                            val mergedSession = diskCached.session.copy(
                                title = summary.title.ifBlank { diskCached.session.title },
                                status = summary.status.ifBlank { diskCached.session.status },
                                updatedAt = maxOf(summary.updatedAt, diskCached.session.updatedAt),
                                currentModelId = diskCached.session.currentModelId.ifBlank { summary.currentModelId },
                                currentModelLabel = diskCached.session.currentModelLabel.ifBlank { summary.currentModelLabel },
                            )
                            diskCached.copy(
                                session = mergedSession,
                                availableModels = cachedModels.ifEmpty { diskCached.availableModels },
                                loading = false,
                                syncing = true,
                                fromCache = true,
                            )
                        }
                    }
                }
            }
        }

        socketClient.sendEnvelope(
            "session.open",
            buildJsonObject {
                put("sessionId", JsonPrimitive(summary.id))
                put("source", JsonPrimitive(summary.source))
            }
        )
        requestMetricsSnapshot(summary.id, summary.source)
    }

    fun openSessionById(sessionId: String, source: String = "antigravity") {
        if (sessionId.isBlank()) return
        val existing = _sessions.value.find { it.id == sessionId }
            ?: SessionSummaryUi(
                id = sessionId,
                source = source,
                title = "加载会话中...",
                project = source.uppercase(),
                workspacePath = "",
                preview = "",
                status = "done",
                stepCount = 0,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = System.currentTimeMillis()
            )
        openSession(existing)
    }

    fun closeActiveSession() {
        _activeDetail.value = null
        _activeMetrics.value = null
        _preview.value = null
        socketClient.sendEnvelope("session.close", buildJsonObject { })
    }

    fun requestArtifactPreview(sessionId: String, item: ArtifactItemUi) {
        _preview.value = ArtifactPreviewState(
            loading = true,
            artifactId = item.artifactId,
            name = item.name,
            kind = item.kind,
            mimeType = item.mimeType,
            addLines = item.addLines,
            delLines = item.delLines,
        )
        socketClient.sendEnvelope(
            "artifact.get",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("artifactId", JsonPrimitive(item.artifactId))
                put("mode", JsonPrimitive("preview"))
            }
        )
    }

    fun dismissPreview() {
        _preview.value = null
    }

    fun requestArtifactDownload(sessionId: String, artifactId: String, fileName: String) {
        _downloadStatus.value = DownloadStatusState(
            active = true,
            fileName = fileName,
            progress = 0.02f,
            savedMessage = null,
            error = null
        )
        socketClient.sendEnvelope(
            "artifact.get",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("artifactId", JsonPrimitive(artifactId))
                put("mode", JsonPrimitive("download"))
            }
        )
    }

    fun clearDownloadBanner() {
        _downloadStatus.value = DownloadStatusState()
    }

    fun clearUploadBanner() {
        _uploadStatus.value = UploadStatusState()
    }

    fun requestDirectFileDownload(sessionId: String, filePath: String, displayName: String) {
        if (filePath.isBlank()) return
        val name = displayName.ifBlank {
            filePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "download.bin" }
        }
        _downloadStatus.value = DownloadStatusState(
            active = true,
            fileName = name,
            progress = 0.03f,
            savedMessage = null,
            error = null
        )
        val sent = socketClient.sendEnvelope(
            "file.download",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("filePath", JsonPrimitive(filePath))
            }
        )
        if (!sent) {
            _downloadStatus.value = DownloadStatusState(
                active = false,
                fileName = name,
                error = "桌面未连接，无法下载文件"
            )
        }
    }

    fun inspectUriMetadata(uri: Uri): StagedAttachmentUi? {
        return runCatching {
            val resolver = appContext.contentResolver
            var name = "upload_${System.currentTimeMillis()}"
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            val mime = resolver.getType(uri) ?: guessLocalMime(name)
            val isImg = mime.startsWith("image/") ||
                name.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".gif") }
            StagedAttachmentUi(
                uriString = uri.toString(),
                fileName = name,
                mimeType = mime,
                sizeBytes = size,
                isImage = isImg
            )
        }.getOrNull()
    }

    private fun guessLocalMime(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".md") -> "text/markdown"
            lower.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    fun uploadAttachmentToDesktop(
        sessionId: String,
        source: String,
        attachment: StagedAttachmentUi,
        deliverToChat: Boolean,
        promptText: String,
    ) {
        scope.launch {
            runCatching {
                val uri = Uri.parse(attachment.uriString)
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件内容")
                if (bytes.isEmpty()) error("所选文件为空")
                if (bytes.size > 64 * 1024 * 1024) error("文件超过 64 MB 上传上限")

                val uploadId = UUID.randomUUID().toString()
                val chunkBytes = 256 * 1024
                val totalChunks = ((bytes.size + chunkBytes - 1) / chunkBytes).coerceAtLeast(1)

                _uploadStatus.value = UploadStatusState(
                    active = true,
                    fileName = attachment.fileName,
                    progress = 0.04f,
                )

                for (i in 0 until totalChunks) {
                    val start = i * chunkBytes
                    val end = minOf(bytes.size, start + chunkBytes)
                    val slice = bytes.copyOfRange(start, end)
                    val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
                    val sent = socketClient.sendEnvelope(
                        "upload.chunk",
                        buildJsonObject {
                            put("uploadId", JsonPrimitive(uploadId))
                            put("sessionId", JsonPrimitive(sessionId))
                            put("source", JsonPrimitive(source))
                            put("fileName", JsonPrimitive(attachment.fileName))
                            put("mimeType", JsonPrimitive(attachment.mimeType))
                            put("chunkIndex", JsonPrimitive(i))
                            put("totalChunks", JsonPrimitive(totalChunks))
                            put("base64Chunk", JsonPrimitive(b64))
                        }
                    )
                    if (!sent) error("桌面未连接，上传已中断")
                    _uploadStatus.value = UploadStatusState(
                        active = true,
                        fileName = attachment.fileName,
                        progress = ((i + 1).toFloat() / totalChunks.toFloat()) * 0.92f
                    )
                    if (totalChunks > 1) delay(8L)
                }

                val selectedModelId = _activeDetail.value
                    ?.takeIf { it.session.id == sessionId }
                    ?.session?.currentModelId
                    ?.takeIf { it.isNotBlank() }

                if (deliverToChat) {
                    _activeDetail.update { cur ->
                        if (cur == null || cur.session.id != sessionId) cur
                        else {
                            val tag = if (attachment.isImage) "🖼️ [发送图片: ${attachment.fileName}]"
                            else "📎 [上传文件: ${attachment.fileName}]"
                            val combined = if (promptText.isNotBlank()) "$tag\n${promptText.trim()}" else tag
                            cur.copy(
                                messages = cur.messages + ChatMessageUi(
                                    id = "local-up-${System.currentTimeMillis()}",
                                    role = "user",
                                    text = combined,
                                    thinkingSummary = "",
                                    toolSteps = emptyList(),
                                    status = "done",
                                    ts = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                socketClient.sendEnvelope(
                    "upload.complete",
                    buildJsonObject {
                        put("uploadId", JsonPrimitive(uploadId))
                        put("sessionId", JsonPrimitive(sessionId))
                        put("source", JsonPrimitive(source))
                        put("deliverToChat", JsonPrimitive(deliverToChat))
                        put("promptText", JsonPrimitive(promptText.trim()))
                        if (selectedModelId != null) {
                            put("modelId", JsonPrimitive(selectedModelId))
                        }
                    }
                )
            }.onFailure { e ->
                _uploadStatus.value = UploadStatusState(
                    active = false,
                    fileName = attachment.fileName,
                    error = "上传失败：${e.message}"
                )
            }
        }
    }

    private fun handleFrame(frame: RelayFrame) {
        when (frame.type) {
            "quota.data" -> handleQuotaData(frame.payload)
            "metrics.data" -> handleMetricsData(frame.payload)
            "session.list" -> handleSessionList(frame.payload)
            "session.stream" -> handleSessionStream(frame.payload)
            "model.list" -> handleModelList(frame.payload)
            "session.model.ack" -> handleSessionModelAck(frame.payload)
            "artifact.data" -> handleArtifactData(frame.payload)
            "artifact.chunk" -> handleArtifactChunk(frame.payload)
            "upload.ack" -> handleUploadAck(frame.payload)
            "approval.list" -> handleApprovalList(frame.payload)
            "approval.pending" -> handleSingleApprovalPending(frame.payload)
            "approval.ack" -> handleApprovalAck(frame.payload)
            "chat.ack" -> handleChatAck(frame.payload)
            "event.notify" -> {
                if (frame.payload["category"]?.jsonPrimitive?.contentOrNull == "approval.pending") {
                    handleSingleApprovalPending(frame.payload)
                }
            }
            "desktop_offline" -> {
                _activeDetail.update { it?.copy(syncing = false, loading = false) }
                _actionFeedback.value = ActionFeedbackState(
                    sending = false,
                    banner = "桌面桥当前离线，写操作未执行",
                    isError = true
                )
            }
        }
    }

    private fun parseApprovalItem(o: JsonObject): PendingApprovalUi? {
        val aid = o["approvalId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (aid.isBlank()) return null
        return PendingApprovalUi(
            approvalId = aid,
            sessionId = o["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
            source = o["source"]?.jsonPrimitive?.contentOrNull ?: "antigravity",
            project = o["project"]?.jsonPrimitive?.contentOrNull ?: "Project",
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "待确认操作",
            commandPreview = o["commandPreview"]?.jsonPrimitive?.contentOrNull
                ?: o["body"]?.jsonPrimitive?.contentOrNull ?: "",
            riskLevel = o["riskLevel"]?.jsonPrimitive?.contentOrNull ?: "high",
            ts = o["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
    }

    private fun handleApprovalList(p: JsonObject) {
        val arr = p["approvals"] as? JsonArray ?: return
        _pendingApprovals.value = arr.mapNotNull { runCatching { parseApprovalItem(it.jsonObject) }.getOrNull() }
            .distinctBy { it.approvalId }
    }

    private fun handleSingleApprovalPending(p: JsonObject) {
        val item = parseApprovalItem(p) ?: return
        _pendingApprovals.update { cur ->
            (listOf(item) + cur.filterNot { it.approvalId == item.approvalId }).take(50)
        }
    }

    private fun handleApprovalAck(p: JsonObject) {
        val ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val aid = p["approvalId"]?.jsonPrimitive?.contentOrNull ?: ""
        val decision = p["decision"]?.jsonPrimitive?.contentOrNull ?: ""
        if (ok) {
            _pendingApprovals.update { list -> list.filterNot { it.approvalId == aid } }
            val word = if (decision == "approve") "已批准执行" else "已拒绝操作"
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "✅ $word", isError = false)
        } else {
            val msg = p["message"]?.jsonPrimitive?.contentOrNull ?: "审批操作未通过"
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = msg, isError = true)
        }
    }

    private fun handleChatAck(p: JsonObject) {
        val ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            val customMsg = p["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val banner = if (customMsg.isNotEmpty()) "✅ $customMsg" else "✅ 消息已送达桌面会话"
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = banner, isError = false)
        } else {
            // Rollback optimistic local bubble when delivery fails so UI never fakes delivery
            _activeDetail.update { cur ->
                cur?.copy(
                    messages = cur.messages.filterNot {
                        it.id.startsWith("local-") || it.id.startsWith("quick-")
                    }
                )
            }
            val code = p["code"]?.jsonPrimitive?.contentOrNull ?: ""
            val rawErr = p["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            val friendlyErr = if (
                code == "codex_active_writer" ||
                rawErr.contains("already has an active writer", ignoreCase = true) ||
                rawErr.contains("thread-store conflict", ignoreCase = true)
            ) {
                "该 Codex 会话正在电脑端使用中，请先在电脑上的 Codex 客户端停下或切换到其他会话后再发"
            } else {
                rawErr
            }
            _actionFeedback.value = ActionFeedbackState(sending = false, banner = "发送失败：$friendlyErr", isError = true)
        }
    }

    private fun handleUploadAck(p: JsonObject) {
        val ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val name = p["fileName"]?.jsonPrimitive?.contentOrNull ?: ""
        if (ok) {
            val savedPath = p["savedPath"]?.jsonPrimitive?.contentOrNull ?: ""
            val delivered = p["deliveredToChat"]?.jsonPrimitive?.booleanOrNull ?: false
            val msg = if (delivered) "✅ 已发送至桌面会话并保存：$savedPath"
            else "✅ 文件已实时上传至电脑：$savedPath"
            _uploadStatus.value = UploadStatusState(
                active = false,
                fileName = name,
                progress = 1f,
                message = msg
            )
        } else {
            _activeDetail.update { cur ->
                cur?.copy(messages = cur.messages.filterNot { it.id.startsWith("local-up-") })
            }
            val err = p["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            _uploadStatus.value = UploadStatusState(
                active = false,
                fileName = name,
                error = "上传失败：$err"
            )
        }
    }

    private fun handleMetricsData(p: JsonObject) {
        val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!enabled) {
            _activeMetrics.value = null
            return
        }
        val sid = p["sessionId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val src = p["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "antigravity" }
        val activeSid = _activeDetail.value?.session?.id
        if (sid.isBlank() || activeSid == null || sid != activeSid) {
            return
        }

        val parsed = SessionMetricsState(
            enabled = true,
            available = p["available"]?.jsonPrimitive?.booleanOrNull ?: true,
            sessionId = sid,
            source = src,
            model = p["model"]?.jsonPrimitive?.contentOrNull ?: "",
            modelId = p["modelId"]?.jsonPrimitive?.contentOrNull ?: "",
            state = p["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
            dataSource = p["dataSource"]?.jsonPrimitive?.contentOrNull ?: "none",
            ttftMs = p["ttftMs"]?.jsonPrimitive?.longOrNull,
            ttftEstimated = p["ttftEstimated"]?.jsonPrimitive?.booleanOrNull ?: false,
            decodeTps = p["decodeTps"]?.jsonPrimitive?.doubleOrNull,
            decodeTpsEstimated = p["decodeTpsEstimated"]?.jsonPrimitive?.booleanOrNull ?: false,
            e2eTps = p["e2eTps"]?.jsonPrimitive?.doubleOrNull,
            e2eTpsEstimated = p["e2eTpsEstimated"]?.jsonPrimitive?.booleanOrNull ?: false,
            tpsWindowSec = p["tpsWindowSec"]?.jsonPrimitive?.intOrNull ?: 3,
            outputTokens = p["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            outputTokensEstimated = p["outputTokensEstimated"]?.jsonPrimitive?.booleanOrNull ?: false,
            thinkingTokens = p["thinkingTokens"]?.jsonPrimitive?.longOrNull,
            responseTokens = p["responseTokens"]?.jsonPrimitive?.longOrNull,
            contextUsed = p["contextUsed"]?.jsonPrimitive?.longOrNull,
            contextMax = p["contextMax"]?.jsonPrimitive?.longOrNull,
            contextPct = p["contextPct"]?.jsonPrimitive?.doubleOrNull,
            cacheHitPct = p["cacheHitPct"]?.jsonPrimitive?.doubleOrNull,
            estCostReply = p["estCostReply"]?.jsonPrimitive?.doubleOrNull,
            estCostSession = p["estCostSession"]?.jsonPrimitive?.doubleOrNull,
            costEstimated = p["costEstimated"]?.jsonPrimitive?.booleanOrNull ?: false,
            priceDefaulted = p["priceDefaulted"]?.jsonPrimitive?.booleanOrNull ?: false,
            pricingLabel = p["pricingLabel"]?.jsonPrimitive?.contentOrNull ?: "",
            pricingUpdatedAt = p["pricingUpdatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
            replyTurns = p["replyTurns"]?.jsonPrimitive?.intOrNull ?: 0,
            sessionTurns = p["sessionTurns"]?.jsonPrimitive?.intOrNull ?: 0,
            ts = p["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
        metricsCache["${src.lowercase()}:$sid"] = parsed
        _activeMetrics.value = parsed
    }

    private fun handleQuotaData(p: JsonObject) {
        val available = p["available"]?.jsonPrimitive?.booleanOrNull ?: false
        val planName = p["planName"]?.jsonPrimitive?.contentOrNull ?: "Antigravity"
        val tierCode = p["tierCode"]?.jsonPrimitive?.contentOrNull ?: ""
        val fetchedAt = p["fetchedAt"]?.jsonPrimitive?.longOrNull
        val fromCache = p["fromCache"]?.jsonPrimitive?.booleanOrNull ?: false
        val error = p["error"]?.jsonPrimitive?.contentOrNull

        fun parseBucket(obj: JsonObject?): QuotaBucketUi? {
            if (obj == null) return null
            return QuotaBucketUi(
                bucketId = obj["bucketId"]?.jsonPrimitive?.contentOrNull ?: "",
                window = obj["window"]?.jsonPrimitive?.contentOrNull ?: "",
                remainingPercent = obj["remainingPercent"]?.jsonPrimitive?.intOrNull ?: 0,
                usedPercent = obj["usedPercent"]?.jsonPrimitive?.intOrNull ?: 0,
                remainingFraction = obj["remainingFraction"]?.jsonPrimitive?.floatOrNull ?: 0f,
                resetAt = obj["resetAt"]?.jsonPrimitive?.longOrNull,
            )
        }

        fun parseGroup(obj: JsonObject?): QuotaGroupCardUi? {
            if (obj == null) return null
            return QuotaGroupCardUi(
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                window5h = parseBucket(obj["window5h"] as? JsonObject),
                window7d = parseBucket(obj["window7d"] as? JsonObject),
            )
        }

        val codexObj = p["codexOpenAi"] as? JsonObject
        val codexUi = if (codexObj != null && (codexObj["available"]?.jsonPrimitive?.booleanOrNull == true)) {
            CodexOpenAiQuotaUi(
                available = true,
                planName = codexObj["planName"]?.jsonPrimitive?.contentOrNull ?: "ChatGPT Plus",
                planType = codexObj["planType"]?.jsonPrimitive?.contentOrNull ?: "plus",
                fetchedAt = codexObj["fetchedAt"]?.jsonPrimitive?.longOrNull,
                resetCreditsCount = codexObj["resetCreditsCount"]?.jsonPrimitive?.intOrNull ?: 0,
                window5h = parseBucket(codexObj["window5h"] as? JsonObject),
                window7d = parseBucket(codexObj["window7d"] as? JsonObject),
            )
        } else null

        _quota.value = QuotaDashboardState(
            available = available,
            planName = planName,
            tierCode = tierCode,
            fetchedAt = fetchedAt,
            fromCache = fromCache,
            error = error,
            gemini = parseGroup(p["gemini"] as? JsonObject),
            claudeGpt = parseGroup(p["claudeGpt"] as? JsonObject),
            codexOpenAi = codexUi,
        )
    }

    private fun handleSessionList(p: JsonObject) {
        val arr = p["sessions"] as? JsonArray ?: return
        val note = p["cdpNote"]?.jsonPrimitive?.contentOrNull ?: ""
        _cdpNote.value = note
        val parsed = arr.mapNotNull { el ->
            runCatching { parseSessionSummary(el.jsonObject) }.getOrNull()
        }
            .filter { it.id.isNotBlank() }
            .distinctBy { "${it.source}:${it.id}" }
        _sessions.value = parsed
        saveRecentSessionsToDisk(parsed)
    }

    private fun parseModelOption(o: JsonObject, defaultSource: String): ModelOptionUi? {
        val id = o["modelId"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        val label = o["label"]?.jsonPrimitive?.contentOrNull?.trim() ?: id
        if (id.isEmpty()) return null
        return ModelOptionUi(
            modelId = id,
            label = label,
            source = o["source"]?.jsonPrimitive?.contentOrNull ?: defaultSource,
            tag = o["tag"]?.jsonPrimitive?.contentOrNull ?: "",
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            remainingPercent = o["remainingPercent"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun handleModelList(p: JsonObject) {
        val agArr = p["antigravityModels"] as? JsonArray
        if (agArr != null) {
            val parsedAg = agArr.mapNotNull { runCatching { parseModelOption(it.jsonObject, "antigravity") }.getOrNull() }
            if (parsedAg.isNotEmpty()) _antigravityModels.value = parsedAg
        }
        val cdxArr = p["codexModels"] as? JsonArray
        if (cdxArr != null) {
            val parsedCdx = cdxArr.mapNotNull { runCatching { parseModelOption(it.jsonObject, "codex") }.getOrNull() }
            if (parsedCdx.isNotEmpty()) _codexModels.value = parsedCdx
        }
        _activeDetail.update { cur ->
            if (cur == null) null
            else {
                val list = if (cur.session.source == "codex") _codexModels.value else _antigravityModels.value
                if (list.isNotEmpty()) cur.copy(availableModels = list) else cur
            }
        }
    }

    private fun handleSessionModelAck(p: JsonObject) {
        val ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            val label = p["currentModelLabel"]?.jsonPrimitive?.contentOrNull ?: "已选模型"
            val id = p["currentModelId"]?.jsonPrimitive?.contentOrNull ?: ""
            val sid = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
            _activeDetail.update { cur ->
                if (cur == null || cur.session.id != sid) cur
                else cur.copy(
                    session = cur.session.copy(
                        currentModelId = id.ifBlank { cur.session.currentModelId },
                        currentModelLabel = label
                    )
                )
            }
            _actionFeedback.value = ActionFeedbackState(
                sending = false,
                banner = "✅ 已切换任务模型为：$label",
                isError = false
            )
        } else {
            val err = p["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            _actionFeedback.value = ActionFeedbackState(
                sending = false,
                banner = "切换模型失败：$err",
                isError = true
            )
        }
    }

    private fun parseSessionSummary(o: JsonObject): SessionSummaryUi {
        return SessionSummaryUi(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            source = o["source"]?.jsonPrimitive?.contentOrNull ?: "antigravity",
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "未命名会话",
            project = o["project"]?.jsonPrimitive?.contentOrNull ?: "默认项目",
            workspacePath = o["workspacePath"]?.jsonPrimitive?.contentOrNull ?: "",
            preview = o["preview"]?.jsonPrimitive?.contentOrNull ?: "",
            status = o["status"]?.jsonPrimitive?.contentOrNull ?: "done",
            stepCount = o["stepCount"]?.jsonPrimitive?.intOrNull ?: 0,
            subagentCount = o["subagentCount"]?.jsonPrimitive?.intOrNull ?: 0,
            runningSubagentCount = o["runningSubagentCount"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            currentModelId = o["currentModelId"]?.jsonPrimitive?.contentOrNull ?: "",
            currentModelLabel = o["currentModelLabel"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun parseChatMessage(o: JsonObject): ChatMessageUi {
        val steps = (o["toolSteps"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()
        return ChatMessageUi(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            role = o["role"]?.jsonPrimitive?.contentOrNull ?: "agent",
            text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
            thinkingSummary = o["thinkingSummary"]?.jsonPrimitive?.contentOrNull ?: "",
            toolSteps = steps,
            status = o["status"]?.jsonPrimitive?.contentOrNull ?: "done",
            ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private fun handleSessionStream(p: JsonObject) {
        val mode = p["mode"]?.jsonPrimitive?.contentOrNull ?: "snapshot"
        val cur = _activeDetail.value ?: return

        if (mode == "live_tail") {
            val sid = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            if (cur.session.id != sid) return
            val liveObj = p["liveMessage"] as? JsonObject ?: return
            val liveMsg = parseChatMessage(liveObj)

            val withoutOldLive = cur.messages.filterNot { it.id == "cdp-live-tail" || it.id == "agent-pending" }
            // Avoid duplicating if identical to last message
            val lastCommitted = withoutOldLive.lastOrNull()
            val updatedMsgs = if (lastCommitted != null && lastCommitted.role == "agent" && lastCommitted.text == liveMsg.text) {
                withoutOldLive
            } else {
                withoutOldLive + liveMsg
            }
            _activeDetail.value = cur.copy(
                session = cur.session.copy(status = if (liveMsg.status == "streaming") "running" else cur.session.status),
                messages = updatedMsgs,
                loading = false,
                syncing = false,
                fromCache = false,
            )
            return
        }

        // mode == "snapshot"
        val sessObj = p["session"] as? JsonObject ?: return
        val parsedSession = parseSessionSummary(sessObj)
        if (cur.session.id != parsedSession.id) return

        val cdpAvail = sessObj["cdpAvailable"]?.jsonPrimitive?.booleanOrNull ?: false
        val msgs = ((p["messages"] as? JsonArray)?.mapIndexedNotNull { idx, el ->
            runCatching {
                val parsed = parseChatMessage(el.jsonObject)
                if (parsed.id.isBlank()) parsed.copy(id = "msg-$idx") else parsed
            }.getOrNull()
        } ?: emptyList()).distinctBy { it.id }

        val subs = ((p["subagents"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                SubagentUi(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    agentName = o["agentName"]?.jsonPrimitive?.contentOrNull ?: "subagent",
                    role = o["role"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = o["status"]?.jsonPrimitive?.contentOrNull ?: "done",
                    stepCount = o["stepCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    preview = o["preview"]?.jsonPrimitive?.contentOrNull ?: "",
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        } ?: emptyList()).distinctBy { it.id }

        val arts = ((p["artifacts"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ArtifactItemUi(
                    artifactId = o["artifactId"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "file",
                    mimeType = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                    sizeBytes = o["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    addLines = o["addLines"]?.jsonPrimitive?.intOrNull ?: 0,
                    delLines = o["delLines"]?.jsonPrimitive?.intOrNull ?: 0,
                    filePath = o["filePath"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }.getOrNull()
        } ?: emptyList()).distinctBy { it.artifactId }

        val streamModels = ((p["availableModels"] as? JsonArray)?.mapNotNull { el ->
            runCatching { parseModelOption(el.jsonObject, parsedSession.source) }.getOrNull()
        } ?: emptyList())
        if (streamModels.isNotEmpty()) {
            if (parsedSession.source == "codex") _codexModels.value = streamModels
            else _antigravityModels.value = streamModels
        }
        val effectiveModels = streamModels.ifEmpty {
            if (parsedSession.source == "codex") _codexModels.value else _antigravityModels.value
        }

        val freshDetail = SessionDetailState(
            session = parsedSession,
            cdpAvailable = cdpAvail,
            availableModels = effectiveModels,
            messages = msgs,
            subagents = subs,
            artifacts = arts,
            loading = false,
            syncing = false,
            fromCache = false,
        )
        _activeDetail.value = freshDetail
        detailCache.put(freshDetail)
    }

    private fun handleArtifactData(p: JsonObject) {
        val ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val artId = p["artifactId"]?.jsonPrimitive?.contentOrNull ?: ""
        if (!ok) {
            val err = p["error"]?.jsonPrimitive?.contentOrNull ?: "读取产物失败"
            if (_preview.value?.artifactId == artId) {
                _preview.update { it?.copy(loading = false, error = err) }
            } else {
                _downloadStatus.value = DownloadStatusState(active = false, error = err)
            }
            return
        }

        val kind = p["kind"]?.jsonPrimitive?.contentOrNull ?: "file"
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val mime = p["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val b64 = p["base64Data"]?.jsonPrimitive?.contentOrNull
        val textContent = p["textContent"]?.jsonPrimitive?.contentOrNull
        val addLines = p["addLines"]?.jsonPrimitive?.intOrNull ?: 0
        val delLines = p["delLines"]?.jsonPrimitive?.intOrNull ?: 0
        val canDl = p["canDownloadFile"]?.jsonPrimitive?.booleanOrNull ?: true

        val imgBytes = if (!b64.isNullOrBlank()) {
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        } else null

        _preview.value = ArtifactPreviewState(
            loading = false,
            artifactId = artId,
            name = name,
            kind = kind,
            mimeType = mime,
            imageBytes = imgBytes,
            textContent = textContent,
            addLines = addLines,
            delLines = delLines,
            canDownload = canDl,
            error = null,
        )
    }

    private fun handleArtifactChunk(p: JsonObject) {
        val transferId = p["transferId"]?.jsonPrimitive?.contentOrNull ?: return
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: "artifact.bin"
        val mime = p["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val idx = p["chunkIndex"]?.jsonPrimitive?.intOrNull ?: 0
        val total = (p["totalChunks"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        val b64 = p["base64Chunk"]?.jsonPrimitive?.contentOrNull ?: return

        val decoded = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        val acc = activeTransfers.getOrPut(transferId) {
            ChunkAccumulator(fileName = name, mimeType = mime, totalChunks = total)
        }
        if (idx in 0 until acc.totalChunks) {
            acc.chunks[idx] = decoded
        }

        val receivedCount = acc.chunks.count { it != null }
        val prog = receivedCount.toFloat() / acc.totalChunks.toFloat()
        _downloadStatus.value = DownloadStatusState(
            active = receivedCount < acc.totalChunks,
            fileName = name,
            progress = prog,
        )

        if (receivedCount == acc.totalChunks) {
            activeTransfers.remove(transferId)
            val out = ByteArrayOutputStream()
            for (c in acc.chunks) {
                if (c != null) out.write(c)
            }
            saveBytesToDownloads(name, mime, out.toByteArray())
        }
    }

    private fun saveBytesToDownloads(fileName: String, mimeType: String, bytes: ByteArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClawBridge")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: error("无法创建下载文件项")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)

                _downloadStatus.value = DownloadStatusState(
                    active = false,
                    fileName = fileName,
                    progress = 1f,
                    savedMessage = "已保存至 Downloads/ClawBridge/$fileName",
                    savedUriString = uri.toString(),
                    mimeType = mimeType,
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ClawBridge"
                )
                dir.mkdirs()
                val outFile = File(dir, fileName)
                outFile.writeBytes(bytes)
                _downloadStatus.value = DownloadStatusState(
                    active = false,
                    fileName = fileName,
                    progress = 1f,
                    savedMessage = "已保存至 ${outFile.absolutePath}",
                    mimeType = mimeType,
                )
            }
        }.onFailure { e ->
            _downloadStatus.value = DownloadStatusState(
                active = false,
                fileName = fileName,
                error = "保存失败: ${e.message}",
            )
        }
    }

    // Spec 3.3: Persist ONLY recent session summary list to disk, never full message history
    private fun saveRecentSessionsToDisk(list: List<SessionSummaryUi>) {
        runCatching {
            val arr = buildJsonArray {
                list.take(40).forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(s.id))
                            put("source", JsonPrimitive(s.source))
                            put("title", JsonPrimitive(s.title))
                            put("project", JsonPrimitive(s.project))
                            put("workspacePath", JsonPrimitive(s.workspacePath))
                            put("preview", JsonPrimitive(s.preview))
                            put("status", JsonPrimitive(s.status))
                            put("stepCount", JsonPrimitive(s.stepCount))
                            put("subagentCount", JsonPrimitive(s.subagentCount))
                            put("runningSubagentCount", JsonPrimitive(s.runningSubagentCount))
                            put("updatedAt", JsonPrimitive(s.updatedAt))
                        }
                    )
                }
            }
            File(appContext.filesDir, "recent_sessions.json").writeText(arr.toString())
        }
    }

    private fun loadRecentSessionsFromDisk() {
        runCatching {
            val f = File(appContext.filesDir, "recent_sessions.json")
            if (!f.exists()) return
            val arr = json.parseToJsonElement(f.readText()).jsonArray
            _sessions.value = arr.mapNotNull { el ->
                runCatching { parseSessionSummary(el.jsonObject) }.getOrNull()
            }
                .filter { it.id.isNotBlank() }
                .distinctBy { "${it.source}:${it.id}" }
        }
    }
}
