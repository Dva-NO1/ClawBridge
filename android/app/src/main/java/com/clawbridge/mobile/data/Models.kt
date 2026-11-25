package com.clawbridge.mobile.data

data class QuotaBucketUi(
    val bucketId: String,
    val window: String,
    val remainingPercent: Int,
    val usedPercent: Int,
    val remainingFraction: Float,
    val resetAt: Long?,
)

data class QuotaGroupCardUi(
    val title: String,
    val description: String,
    val window5h: QuotaBucketUi?,
    val window7d: QuotaBucketUi?,
)

data class CodexOpenAiQuotaUi(
    val available: Boolean = false,
    val planName: String = "ChatGPT Plus",
    val planType: String = "plus",
    val fetchedAt: Long? = null,
    val resetCreditsCount: Int = 0,
    val window5h: QuotaBucketUi? = null,
    val window7d: QuotaBucketUi? = null,
)

data class QuotaDashboardState(
    val available: Boolean = false,
    val planName: String = "Antigravity",
    val tierCode: String = "",
    val fetchedAt: Long? = null,
    val fromCache: Boolean = false,
    val error: String? = null,
    val gemini: QuotaGroupCardUi? = null,
    val claudeGpt: QuotaGroupCardUi? = null,
    val codexOpenAi: CodexOpenAiQuotaUi? = null,
) {
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = fetchedAt ?: return true
        return (nowMs - ts) > 15 * 60 * 1000L
    }
}

data class ModelOptionUi(
    val modelId: String,
    val label: String,
    val source: String, // "antigravity" | "codex"
    val tag: String = "",
    val description: String = "",
    val remainingPercent: Int? = null,
)

data class SessionSummaryUi(
    val id: String,
    val source: String, // "antigravity" | "codex"
    val title: String,
    val project: String,
    val workspacePath: String,
    val preview: String,
    val status: String, // "running" | "waiting_approval" | "done" | "error"
    val stepCount: Int,
    val subagentCount: Int,
    val runningSubagentCount: Int,
    val updatedAt: Long,
    val currentModelId: String = "",
    val currentModelLabel: String = "",
)

data class ChatMessageUi(
    val id: String,
    val role: String, // "user" | "agent"
    val text: String,
    val thinkingSummary: String,
    val toolSteps: List<String>,
    val status: String, // "streaming" | "done" | "error"
    val ts: Long,
)

data class SubagentUi(
    val id: String,
    val agentName: String,
    val role: String,
    val status: String,
    val stepCount: Int,
    val preview: String,
    val updatedAt: Long,
)

data class ArtifactItemUi(
    val artifactId: String,
    val name: String,
    val kind: String, // "image" | "markdown" | "diff" | "file"
    val mimeType: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    val addLines: Int = 0,
    val delLines: Int = 0,
    val filePath: String = "",
)

data class SessionDetailState(
    val session: SessionSummaryUi,
    val cdpAvailable: Boolean = false,
    val availableModels: List<ModelOptionUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val subagents: List<SubagentUi> = emptyList(),
    val artifacts: List<ArtifactItemUi> = emptyList(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val fromCache: Boolean = false,
)

data class SessionMetricsState(
    val enabled: Boolean = true,
    val available: Boolean = false,
    val sessionId: String = "",
    val source: String = "",
    val model: String = "",
    val modelId: String = "",
    val state: String = "idle", // "idle" | "generating"
    val dataSource: String = "none",
    val ttftMs: Long? = null,
    val ttftEstimated: Boolean = false,
    val decodeTps: Double? = null,
    val decodeTpsEstimated: Boolean = false,
    val e2eTps: Double? = null,
    val e2eTpsEstimated: Boolean = false,
    val tpsWindowSec: Int = 3,
    val outputTokens: Long = 0L,
    val outputTokensEstimated: Boolean = false,
    val thinkingTokens: Long? = null,
    val responseTokens: Long? = null,
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    val contextPct: Double? = null,
    val cacheHitPct: Double? = null,
    val estCostReply: Double? = null,
    val estCostSession: Double? = null,
    val costEstimated: Boolean = false,
    val priceDefaulted: Boolean = false,
    val pricingLabel: String = "",
    val pricingUpdatedAt: String = "",
    val replyTurns: Int = 0,
    val sessionTurns: Int = 0,
    val ts: Long = 0L,
) {
    fun hasDisplayableMetrics(): Boolean {
        if (!enabled || !available) return false
        return decodeTps != null ||
            e2eTps != null ||
            ttftMs != null ||
            outputTokens > 0L ||
            contextPct != null ||
            (estCostSession != null && estCostSession > 0.0) ||
            state == "generating"
    }
}

data class ArtifactPreviewState(
    val loading: Boolean = false,
    val artifactId: String = "",
    val name: String = "",
    val kind: String = "",
    val mimeType: String = "",
    val imageBytes: ByteArray? = null,
    val textContent: String? = null,
    val addLines: Int = 0,
    val delLines: Int = 0,
    val canDownload: Boolean = true,
    val error: String? = null,
)

data class DownloadStatusState(
    val active: Boolean = false,
    val fileName: String = "",
    val progress: Float = 0f,
    val savedMessage: String? = null,
    val savedUriString: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
)

data class PendingApprovalUi(
    val approvalId: String,
    val sessionId: String,
    val source: String,
    val project: String,
    val title: String,
    val commandPreview: String,
    val riskLevel: String, // "low" | "high"
    val ts: Long,
)

data class ActionFeedbackState(
    val sending: Boolean = false,
    val banner: String? = null,
    val isError: Boolean = false,
)

data class UploadStatusState(
    val active: Boolean = false,
    val fileName: String = "",
    val progress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
)

data class StagedAttachmentUi(
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean,
)

data class SavedScrollPosition(
    val anchorMessageId: String,
    val index: Int,
    val offset: Int,
)
