package com.clawbridge.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clawbridge.mobile.data.ActionFeedbackState
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.ArtifactItemUi
import com.clawbridge.mobile.data.ChatMessageUi
import com.clawbridge.mobile.data.DownloadStatusState
import com.clawbridge.mobile.data.ModelOptionUi
import com.clawbridge.mobile.data.SessionDetailCache
import com.clawbridge.mobile.data.SessionDetailState
import com.clawbridge.mobile.data.SessionMetricsState
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.data.SubagentUi
import com.clawbridge.mobile.data.UploadStatusState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class MetricsAndCacheAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleDetail(
        sessionId: String = "demo-session-001",
        source: String = "antigravity",
        syncing: Boolean = false,
    ): SessionDetailState {
        val now = System.currentTimeMillis()
        return SessionDetailState(
            session = SessionSummaryUi(
                id = sessionId,
                source = source,
                title = "ClawBridge Real-Time Metrics Demo",
                project = "demo-workspace",
                workspacePath = "/workspace/demo",
                preview = "Live TPS, TTFT, context usage, and estimated cost",
                status = "running",
                stepCount = 12,
                subagentCount = 1,
                runningSubagentCount = 0,
                updatedAt = now - 5_000L,
                currentModelId = "gemini-4-neon-high",
                currentModelLabel = "Gemini 4 Neon (High)",
            ),
            cdpAvailable = true,
            availableModels = listOf(
                ModelOptionUi("gemini-4-neon-high", "Gemini 4 Neon (High)", "antigravity", "", "", 92),
            ),
            messages = listOf(
                ChatMessageUi("u1", "user", "Show real-time TPS and cost panel.", "", emptyList(), "done", now - 30_000L),
                ChatMessageUi("a1", "agent", "Metrics panel and two-layer cache are active.", "", listOf("edit_file"), "streaming", now - 2_000L),
            ),
            subagents = listOf(SubagentUi("sub-1", "Metrics", "Audit", "done", 2, "OK", now - 10_000L)),
            artifacts = listOf(ArtifactItemUi("art-1", "prices.json", "file", "application/json", 4096L, now - 8_000L)),
            loading = false,
            syncing = syncing,
            fromCache = syncing,
        )
    }

    @Test
    fun metricsCollapsedAndExpandedRenderCleanlyOn360dp() {
        val detail = sampleDetail()
        val exactMetrics = SessionMetricsState(
            enabled = true,
            available = true,
            sessionId = detail.session.id,
            source = "antigravity",
            model = "Gemini 4 Neon (High)",
            state = "idle",
            dataSource = "hub_rpc",
            ttftMs = 9205L,
            decodeTps = 157.0,
            e2eTps = 110.5,
            outputTokens = 41200L,
            contextUsed = 252_000L,
            contextMax = 512_000L,
            contextPct = 49.2,
            cacheHitPct = 99.1,
            estCostReply = 1.59,
            estCostSession = 46.78,
        )
        composeRule.setContent {
            ClawBridgeTheme {
                SessionDetailScreen(
                    detail = detail,
                    metrics = exactMetrics,
                    initialMetricsExpanded = true,
                    appSettings = AppSettings(),
                    settingsStore = null,
                    desktopOnline = true,
                    sessionApprovals = emptyList(),
                    actionFeedback = ActionFeedbackState(),
                    previewState = null,
                    downloadStatus = DownloadStatusState(),
                    uploadStatus = UploadStatusState(),
                    onBack = {}, onRefresh = {}, onSelectModel = {}, onRefreshModels = {},
                    onSendChat = {}, onSendQuickCommand = { _, _ -> },
                    onInspectUri = { null }, onUploadAttachment = { _, _, _ -> },
                    onClearUploadBanner = {}, onRespondApproval = { _, _, _ -> },
                    onClearActionFeedback = {}, onPreviewArtifact = {}, onDismissPreview = {},
                    onDownloadArtifact = { _, _ -> }, onDirectFileDownload = { _, _ -> },
                    onClearDownloadBanner = {}, onOpenDownloadedUri = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("session_metrics_drawer").assertIsDisplayed()
        composeRule.onNodeWithText("9.21s").assertIsDisplayed()
        composeRule.onNodeWithText("111 t/s").assertIsDisplayed()
        composeRule.onNodeWithText("99.1%").assertIsDisplayed()
        composeRule.onNodeWithText("按公开 API 单价估算").assertIsDisplayed()

        composeRule.onNodeWithTag("session_metrics_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("session_metrics_drawer").assertDoesNotExist()
    }

    @Test
    fun metricsHiddenWhenMismatchedOrDisabled() {
        val detail = sampleDetail(sessionId = "sess-A")
        val wrong = SessionMetricsState(enabled = true, available = true, sessionId = "sess-B", decodeTps = 120.0, outputTokens = 500L)
        composeRule.setContent {
            ClawBridgeTheme {
                SessionDetailScreen(
                    detail = detail,
                    metrics = wrong,
                    appSettings = AppSettings(),
                    settingsStore = null,
                    desktopOnline = true,
                    sessionApprovals = emptyList(),
                    actionFeedback = ActionFeedbackState(),
                    previewState = null,
                    downloadStatus = DownloadStatusState(),
                    uploadStatus = UploadStatusState(),
                    onBack = {}, onRefresh = {}, onSelectModel = {}, onRefreshModels = {},
                    onSendChat = {}, onSendQuickCommand = { _, _ -> },
                    onInspectUri = { null }, onUploadAttachment = { _, _, _ -> },
                    onClearUploadBanner = {}, onRespondApproval = { _, _, _ -> },
                    onClearActionFeedback = {}, onPreviewArtifact = {}, onDismissPreview = {},
                    onDownloadArtifact = { _, _ -> }, onDirectFileDownload = { _, _ -> },
                    onClearDownloadBanner = {}, onOpenDownloadedUri = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("session_metrics_bar").assertDoesNotExist()
    }

    @Test
    fun sessionDetailCachePersistsAndEvictsLru() {
        val tmpDir = Files.createTempDirectory("cb-oss-cache-test").toFile()
        try {
            val cache1 = SessionDetailCache(tmpDir, maxEntries = 2)
            cache1.put(sampleDetail(sessionId = "s1"))
            Thread.sleep(15)
            cache1.put(sampleDetail(sessionId = "s2", source = "codex"))
            assertNotNull(cache1.getMemory("antigravity", "s1"))

            val cache2 = SessionDetailCache(tmpDir, maxEntries = 2)
            assertNull(cache2.getMemory("codex", "s2"))
            val diskHit = cache2.get("codex", "s2")
            assertNotNull(diskHit)
            assertEquals(2, diskHit!!.messages.size)
            assertTrue(diskHit.fromCache)

            Thread.sleep(15)
            cache2.put(sampleDetail(sessionId = "s3"))
            val files = tmpDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            assertTrue(files.size <= 2)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
