package com.clawbridge.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.clawbridge.mobile.data.ActionFeedbackState
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.ChatMessageUi
import com.clawbridge.mobile.data.CodexOpenAiQuotaUi
import com.clawbridge.mobile.data.DownloadStatusState
import com.clawbridge.mobile.data.ModelOptionUi
import com.clawbridge.mobile.data.ProjectCreationState
import com.clawbridge.mobile.data.QuotaBucketUi
import com.clawbridge.mobile.data.QuotaDashboardState
import com.clawbridge.mobile.data.QuotaGroupCardUi
import com.clawbridge.mobile.data.SessionDetailState
import com.clawbridge.mobile.data.SessionMetricsState
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.data.UploadStatusState
import com.clawbridge.mobile.data.WorkspaceConfigState
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class V190FeaturesAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File by lazy {
        File("build/ui_screenshots_v190").apply {
            mkdirs()
        }
    }

    private fun renderAndSave(fileName: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            ClawBridgeTheme { content() }
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
        assertTrue("Screenshot $fileName failed to save", outFile.exists() && outFile.length() > 1024L)
    }

    private fun onlineConn() = ConnectionUiState(
        socketStatus = SocketStatus.CONNECTED,
        relayUrl = "wss://relay.example.com",
        paired = true,
        desktopOnline = true,
        latencyMs = 19L,
        networkTypeLabel = "5G",
    )

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1100dp-xhdpi")
    fun capture01_AuroraHomeAndSessionsAndDialog360dp() {
        val now = System.currentTimeMillis()
        val quota = QuotaDashboardState(
            available = true,
            planName = "Google AI Ultra",
            tierCode = "ULTRA",
            fetchedAt = now - 20_000L,
            gemini = QuotaGroupCardUi(
                title = "Gemini 配额",
                description = "Gemini 4 Carbon / Neon",
                window5h = QuotaBucketUi("g5", "5h", 92, 8, 0.92f, now + 3 * 3600_000L),
                window7d = QuotaBucketUi("g7", "7d", 86, 14, 0.86f, now + 4 * 86400_000L),
            ),
            codexOpenAi = CodexOpenAiQuotaUi(
                available = true,
                planName = "ChatGPT Plus",
                resetCreditsCount = 2,
                window5h = QuotaBucketUi("c5", "5h", 81, 19, 0.81f, now + 2 * 3600_000L),
                window7d = QuotaBucketUi("c7", "7d", 68, 32, 0.68f, now + 5 * 86400_000L),
            )
        )
        renderAndSave("01_home_aurora_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = onlineConn(),
                tierCode = "ULTRA",
                sessionCount = 8,
                onTopRefresh = {},
            ) {
                HomeScreen(
                    connState = onlineConn(),
                    quotaState = quota,
                    pendingApprovalCount = 0,
                    pendingApprovals = emptyList(),
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
    fun capture02_SessionsAndCreateProjectDialog360dp() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            SessionSummaryUi(
                id = "s1",
                source = "antigravity",
                title = "校园跑腿小程序前端与接口开发",
                project = "校园跑腿小程序",
                workspacePath = "C:/Users/Administrator/Desktop/校园跑腿小程序",
                preview = "已在桌面创建工作目录并生成基础工程结构",
                status = "running",
                stepCount = 12,
                subagentCount = 1,
                runningSubagentCount = 1,
                updatedAt = now - 30_000L,
                currentModelId = "MODEL_PLACEHOLDER_M532",
                currentModelLabel = "Gemini 4 Neon (High)",
            ),
            SessionSummaryUi(
                id = "s2",
                source = "codex",
                title = "ClawBridge v1.9.0 架构与单测校验",
                project = "ClawBridge",
                workspacePath = "C:/Projects/ClawBridge",
                preview = "模型切换防竞态覆盖与 3 秒横幅自动消失验证通过",
                status = "done",
                stepCount = 19,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 300_000L,
                currentModelId = "gpt-6.2-sol#high",
                currentModelLabel = "GPT-6.2 Sol (High)",
            )
        )
        val wsCfg = WorkspaceConfigState(
            projectsRootDir = "C:\\Users\\<User>\\Desktop",
            defaultDesktopDir = "C:\\Users\\<User>\\Desktop",
        )
        val agModels = listOf(
            ModelOptionUi("m1", "Gemini 4 Neon (High)", "antigravity", "Default", "", 92),
            ModelOptionUi("m2", "Gemini 4 Carbon (High)", "antigravity", "", "", 92),
        )

        renderAndSave("02_sessions_new_project_strip_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = onlineConn(),
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
            ) {
                SessionsScreen(
                    connState = onlineConn(),
                    sessions = sessions,
                    pinnedIds = setOf("s1"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                    workspaceConfig = wsCfg,
                    antigravityModels = agModels,
                )
            }
        }
    }

    @Test
    fun capture03_CreateProjectDialogCard360dp() {
        val agModels = listOf(
            ModelOptionUi("m1", "Gemini 4 Neon (High)", "antigravity", "Default", "", 92),
            ModelOptionUi("m2", "Gemini 4 Carbon (High)", "antigravity", "", "", 92),
        )
        renderAndSave("03_create_project_dialog_360dp.png") {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(ClawColors.Canvas),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CreateProjectDialogCard(
                    desktopOnline = true,
                    effectiveProjectsRoot = "C:\\Users\\<User>\\Desktop",
                    projectCreation = ProjectCreationState(),
                    antigravityModels = agModels,
                    codexModels = emptyList(),
                    onOpenSettings = {},
                    onConfirmCreate = { _, _, _, _, _ -> },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1200dp-xhdpi")
    fun capture04_SettingsWorkspaceRootAndChatCobalt360dp() {
        val wsCfg = WorkspaceConfigState(
            projectsRootDir = "C:\\Users\\<User>\\Desktop",
            defaultDesktopDir = "C:\\Users\\<User>\\Desktop",
        )
        renderAndSave("04_settings_workspace_dir_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 2,
                onTabSelected = {},
                connState = onlineConn(),
                tierCode = "ULTRA",
                sessionCount = 8,
                onTopRefresh = {},
            ) {
                SettingsScreenContent(
                    appSettings = AppSettings(projectsRootDir = "C:\\Users\\<User>\\Desktop"),
                    connState = onlineConn(),
                    isWhitelisted = true,
                    workspaceConfig = wsCfg,
                    onSaveProjectsRootDir = {},
                    onResetProjectsRootDir = {},
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

    @Test
    fun verifyChatBannersAutoDismissAfterThreeSeconds() {
        composeRule.mainClock.autoAdvance = false
        val now = System.currentTimeMillis()
        var feedback by mutableStateOf(
            ActionFeedbackState(sending = false, banner = "已切换任务模型为：Gemini 4 Neon (High)", isError = false, seq = 1L)
        )
        var upload by mutableStateOf(
            UploadStatusState(active = false, fileName = "spec.md", progress = 1f, message = "文件已上传至电脑项目目录")
        )
        var download by mutableStateOf(
            DownloadStatusState(active = false, fileName = "out.apk", progress = 1f, savedMessage = "已保存至手机")
        )

        val detail = SessionDetailState(
            session = SessionSummaryUi(
                id = "s-banner",
                source = "antigravity",
                title = "横幅自动消失测试",
                project = "测试项目",
                workspacePath = "C:/Users/Administrator/Desktop/测试项目",
                preview = "",
                status = "done",
                stepCount = 2,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now,
                currentModelId = "m1",
                currentModelLabel = "Gemini 4 Neon (High)",
            ),
            messages = listOf(
                ChatMessageUi("u1", "user", "帮我新建一个青春配色的前端页面", "", emptyList(), "done", now - 5_000L),
                ChatMessageUi("a1", "agent", "好的，已在桌面项目目录初始化工程并启动预览。", "", emptyList(), "done", now - 1_000L),
            ),
        )

        composeRule.setContent {
            ClawBridgeTheme {
                SessionDetailScreen(
                    detail = detail,
                    metrics = SessionMetricsState(
                        enabled = true,
                        available = true,
                        sessionId = "s-banner",
                        decodeTps = 136.5,
                        outputTokens = 820L,
                        estCostReply = 0.012,
                    ),
                    appSettings = AppSettings(),
                    settingsStore = null,
                    desktopOnline = true,
                    sessionApprovals = emptyList(),
                    actionFeedback = feedback,
                    previewState = null,
                    downloadStatus = download,
                    uploadStatus = upload,
                    onBack = {}, onRefresh = {}, onSelectModel = {}, onRefreshModels = {},
                    onSendChat = {}, onSendQuickCommand = { _, _ -> },
                    onInspectUri = { null }, onUploadAttachment = { _, _, _ -> },
                    onClearUploadBanner = { upload = UploadStatusState() },
                    onRespondApproval = { _, _, _ -> },
                    onClearActionFeedback = { feedback = ActionFeedbackState() },
                    onPreviewArtifact = {}, onDismissPreview = {},
                    onDownloadArtifact = { _, _ -> }, onDirectFileDownload = { _, _ -> },
                    onClearDownloadBanner = { download = DownloadStatusState() },
                    onOpenDownloadedUri = { _, _ -> },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(100L)

        // Save screenshot showing cobalt bubble + transient banner before 3s timeout
        var bmp: Bitmap? = null
        composeRule.runOnUiThread {
            val root: View = composeRule.activity.window.decorView
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            bmp = b
        }
        FileOutputStream(File(outDir, "05_chat_cobalt_bubble_360dp.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // At t = 1.5s (< 3s): all 3 banners MUST still be visible
        composeRule.mainClock.advanceTimeBy(1_500L)
        composeRule.onNodeWithTag("chat_action_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_upload_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_download_banner").assertIsDisplayed()

        // Advance past 3.0s total (100 + 1500 + 1600 = 3200ms): all 3 banners MUST auto-dismiss!
        composeRule.mainClock.advanceTimeBy(1_600L)
        composeRule.onNodeWithTag("chat_action_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_upload_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_download_banner").assertDoesNotExist()
    }

    @Test
    fun verifyCreateProjectValidationAndSubmissionAndSettingsDir() {
        var capturedCreate: List<String>? = null
        var savedRoot: String? = null
        var resetCalled = false

        composeRule.setContent {
            ClawBridgeTheme {
                SessionsScreen(
                    connState = onlineConn(),
                    sessions = emptyList(),
                    pinnedIds = emptySet(),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                    workspaceConfig = WorkspaceConfigState(
                        projectsRootDir = "C:\\Users\\<User>\\Desktop",
                        defaultDesktopDir = "C:\\Users\\<User>\\Desktop"
                    ),
                    codexModels = listOf(
                        ModelOptionUi("gpt-6.2-sol#high", "GPT-6.2 Sol (High)", "codex", "Default")
                    ),
                    onCreateProject = { name, src, modelId, modelLabel, prompt ->
                        capturedCreate = listOf(name, src, modelId, modelLabel, prompt)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Open dialog
        composeRule.onNodeWithTag("btn_new_project").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_project_dialog").assertIsDisplayed()

        // Switch to Codex
        composeRule.onNodeWithTag("new_proj_source_codex").performClick()
        composeRule.waitForIdle()

        // Reject illegal slash in folder name
        composeRule.onNodeWithTag("new_proj_name_input").performTextInput("bad/folder")
        composeRule.onNodeWithTag("new_proj_confirm_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("文件夹名不能包含 \\ / : * ? \" < > | 等特殊符号").assertIsDisplayed()

        // Enter valid custom folder name & prompt -> confirm
        composeRule.onNodeWithTag("new_proj_name_input").performTextClearance()
        composeRule.onNodeWithTag("new_proj_name_input").performTextInput("我的新项目_01")
        composeRule.onNodeWithTag("new_proj_prompt_input").performTextInput("帮我搭建项目骨架")
        composeRule.onNodeWithTag("new_proj_confirm_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("我的新项目_01", "codex", "", "", "帮我搭建项目骨架"), capturedCreate)
    }

    @Test
    fun verifySettingsWorkspaceDirSaveAndReset() {
        var savedRoot: String? = null
        var resetCalled = false
        var toggledTaskDone: Boolean? = null

        composeRule.setContent {
            ClawBridgeTheme {
                SettingsScreenContent(
                    appSettings = AppSettings(projectsRootDir = "C:\\Users\\<User>\\Desktop"),
                    connState = onlineConn(),
                    isWhitelisted = true,
                    workspaceConfig = WorkspaceConfigState(
                        projectsRootDir = "C:\\Users\\<User>\\Desktop",
                        defaultDesktopDir = "C:\\Users\\<User>\\Desktop"
                    ),
                    onSaveProjectsRootDir = { savedRoot = it },
                    onResetProjectsRootDir = { resetCalled = true },
                    onSaveRelayConfig = { _, _ -> },
                    onUnpairAndReset = {},
                    onSavePin = {},
                    onUpdatePrefs = { _, _, _, _, _ -> },
                    onUpdateTaskDoneNotify = { toggledTaskDone = it },
                    onRequestBatteryWhitelist = {},
                    onOpenRomAutostartSettings = {},
                    onNavigateHomeForPair = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_projects_root_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("settings_projects_root_input").performTextInput("D:\\MyProjects")
        composeRule.onNodeWithTag("settings_save_projects_root_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("D:\\MyProjects", savedRoot)

        composeRule.onNodeWithTag("settings_reset_projects_root_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(resetCalled)

        composeRule.onNodeWithTag("switch_task_done_notify").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(false, toggledTaskDone)
    }
}
