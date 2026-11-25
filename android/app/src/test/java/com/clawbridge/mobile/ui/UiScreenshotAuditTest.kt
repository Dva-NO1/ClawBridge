package com.clawbridge.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.clawbridge.mobile.data.ActionFeedbackState
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.ArtifactItemUi
import com.clawbridge.mobile.data.ChatMessageUi
import com.clawbridge.mobile.data.CodexOpenAiQuotaUi
import com.clawbridge.mobile.data.DownloadStatusState
import com.clawbridge.mobile.data.ModelOptionUi
import com.clawbridge.mobile.data.PendingApprovalUi
import com.clawbridge.mobile.data.QuotaBucketUi
import com.clawbridge.mobile.data.QuotaDashboardState
import com.clawbridge.mobile.data.QuotaGroupCardUi
import com.clawbridge.mobile.data.SessionDetailState
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.data.SubagentUi
import com.clawbridge.mobile.data.UploadStatusState
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class UiScreenshotAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File by lazy {
        val path = System.getProperty("clawbridge.screenshot.dir")
            ?: "build/ui_screenshots"
        File(path).apply { mkdirs() }
    }

    private fun sampleConnState() = ConnectionUiState(
        socketStatus = SocketStatus.CONNECTED,
        relayUrl = "wss://relay.example.com",
        paired = true,
        desktopOnline = true,
        latencyMs = 24L,
        networkTypeLabel = "5G",
    )

    private fun sampleQuotaState(): QuotaDashboardState {
        val now = System.currentTimeMillis()
        return QuotaDashboardState(
            available = true,
            planName = "Google AI Ultra",
            tierCode = "ULTRA",
            fetchedAt = now - 45_000L,
            fromCache = false,
            gemini = QuotaGroupCardUi(
                title = "Gemini 配额",
                description = "Gemini 4 Argon / Carbon / Neon",
                window5h = QuotaBucketUi("g5h", "5h", 90, 10, 0.90f, now + 3 * 3600_000L),
                window7d = QuotaBucketUi("g7d", "7d", 94, 6, 0.94f, now + 4 * 86400_000L),
            ),
            claudeGpt = QuotaGroupCardUi(
                title = "Claude / GPT 配额",
                description = "Antigravity 附赠池 · Claude 5.5 / GPT",
                window5h = QuotaBucketUi("c5h", "5h", 100, 0, 1.00f, now + 5 * 3600_000L),
                window7d = QuotaBucketUi("c7d", "7d", 67, 33, 0.67f, now + 2 * 86400_000L),
            ),
            codexOpenAi = CodexOpenAiQuotaUi(
                available = true,
                planName = "ChatGPT Plus",
                planType = "plus",
                fetchedAt = now - 30_000L,
                resetCreditsCount = 1,
                window5h = QuotaBucketUi("o5h", "5h", 78, 22, 0.78f, now + 2 * 3600_000L),
                window7d = QuotaBucketUi("o7d", "7d", 62, 38, 0.62f, now + 5 * 86400_000L),
            )
        )
    }

    private fun sampleApprovals(): List<PendingApprovalUi> {
        val now = System.currentTimeMillis()
        return listOf(
            PendingApprovalUi(
                approvalId = "appr-1",
                sessionId = "11111111-1111-4111-8111-111111111111",
                source = "antigravity",
                project = "SampleProject / ClawBridge",
                title = "允许执行 PowerShell 发布构建并签名 APK",
                commandPreview = ".\\gradlew.bat assembleRelease --no-daemon && apksigner verify dist\\ClawBridge-v1.6.0-release.apk",
                riskLevel = "high",
                ts = now - 120_000L,
            )
        )
    }

    private fun sampleSessions(): List<SessionSummaryUi> {
        val now = System.currentTimeMillis()
        return listOf(
            SessionSummaryUi(
                id = "11111111-1111-4111-8111-111111111111",
                source = "antigravity",
                title = "ClawBridge UI 全面优化与 v1.6.0 构建",
                project = "SampleProject",
                workspacePath = "/workspace/sample-project",
                preview = "已完成首页、会话列表、聊天页与设置页 ChatGPT 深色风格统一重构，正在执行截图自检。",
                status = "running",
                stepCount = 42,
                subagentCount = 2,
                runningSubagentCount = 1,
                updatedAt = now - 60_000L,
                currentModelId = "gemini-4-carbon-medium",
                currentModelLabel = "Gemini 4 Carbon (Medium)",
            ),
            SessionSummaryUi(
                id = "codex-001",
                source = "codex",
                title = "Codex 桌面桥中继断线重连策略优化",
                project = "SampleProject",
                workspacePath = "/workspace/sample-project",
                preview = "等待审批：执行自动化端到端回归测试脚本。",
                status = "waiting_approval",
                stepCount = 18,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 600_000L,
                currentModelId = "gpt-5.4",
                currentModelLabel = "GPT-5.4",
            ),
            SessionSummaryUi(
                id = "agy-002",
                source = "antigravity",
                title = "M5 推送通道与桌面托盘静默常驻",
                project = "SampleProject",
                workspacePath = "/workspace/sample-project",
                preview = "已交付无黑窗托盘版 ClawBridgeDesktop.exe 与自定义多分辨率应用图标。",
                status = "done",
                stepCount = 31,
                subagentCount = 1,
                runningSubagentCount = 0,
                updatedAt = now - 3600_000L,
                currentModelId = "claude-5.5-sonnet",
                currentModelLabel = "Claude 5.5 Sonnet",
            ),
            SessionSummaryUi(
                id = "codex-002",
                source = "codex",
                title = "Aliyun ECS Nginx WSS 证书续签检查",
                project = "Relay 运维",
                workspacePath = "c:/ops/relay",
                preview = "证书有效期剩余 74 天，心跳探活正常。",
                status = "done",
                stepCount = 9,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 86400_000L,
                currentModelId = "gpt-5.4-mini",
                currentModelLabel = "GPT-5.4 Mini",
            ),
        )
    }

    private fun sampleSettings() = AppSettings(
        relayUrl = "wss://relay.example.com",
        deviceId = "33333333-3333-4333-8333-333333333333",
        deviceToken = "tok-sample-redacted",
        pairedBridgeId = "desktop-bridge-win11-8892",
        trustSelfSigned = false,
        pinHash = "hashed",
        pinSalt = "salted",
        requirePinForSend = false,
        notificationsEnabled = true,
        dndEnabled = true,
        dndStart = "23:30",
        dndEnd = "07:00",
        pinnedSessionIds = setOf("11111111-1111-4111-8111-111111111111"),
    )

    private fun renderAndSave(fileName: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            ClawBridgeTheme {
                content()
            }
        }
        composeRule.waitForIdle()
        var bitmap: Bitmap? = null
        composeRule.runOnUiThread {
            val root: View = composeRule.activity.window.decorView
            val w = root.width.coerceAtLeast(1)
            val h = root.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            root.draw(canvas)
            bitmap = bmp
        }
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        require(outFile.exists() && outFile.length() > 1024L) { "Screenshot $fileName failed to save" }
    }

    @Test
    fun capture01_HomeViewport360dp() {
        val conn = sampleConnState()
        val quota = sampleQuotaState()
        val approvals = sampleApprovals()
        renderAndSave("01_home_top_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = conn,
                tierCode = quota.tierCode,
                sessionCount = 4,
                onTopRefresh = {},
            ) {
                HomeScreen(
                    connState = conn,
                    quotaState = quota,
                    pendingApprovalCount = approvals.size,
                    pendingApprovals = approvals,
                    hasPinConfigured = true,
                    onApproveItem = {},
                    onRejectItem = {},
                    onPairSubmit = {},
                    onPingOrReconnect = {},
                    onOpenSessions = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1560dp-xhdpi")
    fun capture02_HomeFullQuotas360dp() {
        val conn = sampleConnState()
        val quota = sampleQuotaState()
        val approvals = sampleApprovals()
        renderAndSave("02_home_full_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = conn,
                tierCode = quota.tierCode,
                sessionCount = 4,
                onTopRefresh = {},
            ) {
                HomeScreen(
                    connState = conn,
                    quotaState = quota,
                    pendingApprovalCount = approvals.size,
                    pendingApprovals = approvals,
                    hasPinConfigured = true,
                    onApproveItem = {},
                    onRejectItem = {},
                    onPairSubmit = {},
                    onPingOrReconnect = {},
                    onOpenSessions = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun capture03_Sessions360dp() {
        val conn = sampleConnState()
        val sessions = sampleSessions()
        renderAndSave("03_sessions_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = conn,
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
            ) {
                SessionsScreen(
                    connState = conn,
                    sessions = sessions,
                    pinnedIds = setOf("11111111-1111-4111-8111-111111111111"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                )
            }
        }
    }

    @Test
    fun capture04_ChatDetail360dp() {
        val now = System.currentTimeMillis()
        val session = sampleSessions().first()
        val detail = SessionDetailState(
            session = session,
            cdpAvailable = true,
            availableModels = listOf(
                ModelOptionUi("gemini-4-carbon-medium", "Gemini 4 Carbon (Medium)", "antigravity", "推荐", "", 90),
                ModelOptionUi("claude-5.5-sonnet", "Claude 5.5 Sonnet", "antigravity", "附赠池", "", 67),
            ),
            messages = listOf(
                ChatMessageUi(
                    id = "m1",
                    role = "user",
                    text = "请按照 ChatGPT 深色风格优化全 App UI，并修复首页配额标题重叠与中继地址截断问题。",
                    thinkingSummary = "",
                    toolSteps = emptyList(),
                    status = "done",
                    ts = now - 300_000L,
                ),
                ChatMessageUi(
                    id = "m2",
                    role = "agent",
                    text = "已完成全部页面 UI 重构与视觉规范统一。\n\n```kotlin\nobject ClawColors {\n    val Canvas = Color(0xFF141414)\n    val Surface = Color(0xFF1E1E1E)\n}\n```\n\n安装包已就绪：\n- [ClawBridge-v1.6.0-release.apk](file:////workspace/sample-project/dist/ClawBridge-v1.6.0-release.apk)",
                    thinkingSummary = "审查 5 个核心页面排版，确认无文字重叠、无截断。",
                    toolSteps = listOf("edit_file: Theme.kt", "edit_file: ClawBridgeScreens.kt"),
                    status = "done",
                    ts = now - 60_000L,
                ),
            ),
            subagents = listOf(
                SubagentUi("sub-1", "UI Auditor", "Design Check", "done", 6, "360dp 屏幕无截断", now - 90_000L)
            ),
            artifacts = listOf(
                ArtifactItemUi("art-1", "ClawBridge-v1.6.0-release.apk", "file", "application/vnd.android.package-archive", 5_420_000L, now - 30_000L, 0, 0, "/workspace/sample-project/dist/ClawBridge-v1.6.0-release.apk")
            ),
            loading = false,
        )
        renderAndSave("04_chat_detail_360dp.png") {
            SessionDetailScreen(
                detail = detail,
                appSettings = sampleSettings(),
                settingsStore = null,
                desktopOnline = true,
                sessionApprovals = emptyList(),
                actionFeedback = ActionFeedbackState(),
                previewState = null,
                downloadStatus = DownloadStatusState(),
                uploadStatus = UploadStatusState(),
                onBack = {},
                onRefresh = {},
                onSelectModel = {},
                onRefreshModels = {},
                onSendChat = {},
                onSendQuickCommand = { _, _ -> },
                onInspectUri = { null },
                onUploadAttachment = { _, _, _ -> },
                onClearUploadBanner = {},
                onRespondApproval = { _, _, _ -> },
                onClearActionFeedback = {},
                onPreviewArtifact = {},
                onDismissPreview = {},
                onDownloadArtifact = { _, _ -> },
                onDirectFileDownload = { _, _ -> },
                onClearDownloadBanner = {},
                onOpenDownloadedUri = { _, _ -> },
            )
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1620dp-xhdpi")
    fun capture05_SettingsFull360dp() {
        val conn = sampleConnState()
        renderAndSave("05_settings_full_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 2,
                onTabSelected = {},
                connState = conn,
                tierCode = "ULTRA",
                sessionCount = 4,
                onTopRefresh = {},
            ) {
                SettingsScreenContent(
                    appSettings = sampleSettings(),
                    connState = conn,
                    isWhitelisted = true,
                    onSaveRelayConfig = { _, _ -> },
                    onUnpairAndReset = {},
                    onSavePin = {},
                    onUpdatePrefs = { _, _, _, _, _ -> },
                    onRequestBatteryWhitelist = {},
                    onOpenRomAutostartSettings = {},
                    onNavigateHomeForPair = {},
                )
            }
        }
    }
}
