package com.clawbridge.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.clawbridge.mobile.data.ActionFeedbackState
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.ChatMessageUi
import com.clawbridge.mobile.data.CodexOpenAiQuotaUi
import com.clawbridge.mobile.data.DownloadStatusState
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
import org.junit.Assert.assertFalse
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
class ThemeSystemAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File by lazy {
        File("build/ui_screenshots_v191").apply {
            mkdirs()
        }
    }

    private fun onlineConn() = ConnectionUiState(
        socketStatus = SocketStatus.CONNECTED,
        relayUrl = "wss://relay.example.com",
        paired = true,
        desktopOnline = true,
        latencyMs = 18L,
        networkTypeLabel = "5G",
    )

    private fun renderWithThemeAndSave(
        fileName: String,
        themeConfig: ThemeConfig,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            ClawBridgeTheme(themeConfig = themeConfig) {
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
            root.draw(Canvas(bmp))
            bitmap = bmp
        }
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        assertTrue("Screenshot $fileName failed to save", outFile.exists() && outFile.length() > 1024L)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1060dp-xhdpi")
    fun capture01_DaylightModeHome360dp() {
        val now = System.currentTimeMillis()
        val quota = QuotaDashboardState(
            available = true,
            planName = "Google AI Ultra",
            tierCode = "ULTRA",
            fetchedAt = now - 15_000L,
            gemini = QuotaGroupCardUi(
                title = "Gemini 配额",
                description = "Gemini 4 Carbon / Neon",
                window5h = QuotaBucketUi("g5", "5h", 94, 6, 0.94f, now + 3 * 3600_000L),
                window7d = QuotaBucketUi("g7", "7d", 88, 12, 0.88f, now + 4 * 86400_000L),
            ),
            codexOpenAi = CodexOpenAiQuotaUi(
                available = true,
                planName = "ChatGPT Plus",
                resetCreditsCount = 2,
                window5h = QuotaBucketUi("c5", "5h", 83, 17, 0.83f, now + 2 * 3600_000L),
                window7d = QuotaBucketUi("c7", "7d", 71, 29, 0.71f, now + 5 * 86400_000L),
            )
        )
        renderWithThemeAndSave("01_daylight_home_360dp.png", ThemeConfig(isDarkMode = false)) {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = onlineConn(),
                tierCode = "ULTRA",
                sessionCount = 6,
                isDarkMode = false,
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
    fun capture02_DaylightModeChat360dp() {
        val now = System.currentTimeMillis()
        val detail = SessionDetailState(
            session = SessionSummaryUi(
                id = "s-light",
                source = "antigravity",
                title = "白天模式与自定义取色面板验收",
                project = "ClawBridge",
                workspacePath = "C:/Projects/ClawBridge",
                preview = "",
                status = "running",
                stepCount = 8,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now,
                currentModelId = "m1",
                currentModelLabel = "Gemini 4 Neon (High)",
            ),
            messages = listOf(
                ChatMessageUi(
                    "u1",
                    "user",
                    "加一个可以切换 app 颜色的功能，各个大板块可以从取色器选择，新增一键黑夜和白天模式切换",
                    "",
                    emptyList(),
                    "done",
                    now - 10_000L
                ),
                ChatMessageUi(
                    "a1",
                    "agent",
                    "已完成昼夜双模式与六大板块 HSV 全彩取色器！您现在可以在右上角一键切换白天/黑夜，也可以单独挑选背景、卡片、顶底栏、主按钮、进度条和聊天气泡颜色。\n\n```kotlin\nval daylight = ThemeConfig(isDarkMode = false)\n```",
                    "验证白天模式文字对比度与气泡层次",
                    listOf("write_file: Theme.kt", "write_file: ThemeColorPicker.kt"),
                    "done",
                    now - 2_000L
                ),
            ),
        )
        renderWithThemeAndSave("02_daylight_chat_360dp.png", ThemeConfig(isDarkMode = false)) {
            SessionDetailScreen(
                detail = detail,
                metrics = SessionMetricsState(
                    enabled = true,
                    available = true,
                    sessionId = "s-light",
                    state = "generating",
                    decodeTps = 148.2,
                    outputTokens = 960L,
                    estCostReply = 0.015,
                ),
                appSettings = AppSettings(themeConfig = ThemeConfig(isDarkMode = false)),
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
                isDarkMode = false,
            )
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h1060dp-xhdpi")
    fun capture03_SettingsThemeCustomizer360dp() {
        val cfg = ThemeConfig(isDarkMode = false, lightOverrides = LIGHT_THEME_PRESETS[1].overrides)
        renderWithThemeAndSave("03_settings_peach_daylight_360dp.png", cfg) {
            ClawBridgeRootShell(
                currentTab = 2,
                onTabSelected = {},
                connState = onlineConn(),
                tierCode = "ULTRA",
                sessionCount = 6,
                isDarkMode = false,
                onTopRefresh = {},
            ) {
                SettingsScreenContent(
                    appSettings = AppSettings(
                        projectsRootDir = "C:\\Users\\<User>\\Desktop",
                        themeConfig = cfg
                    ),
                    connState = onlineConn(),
                    isWhitelisted = true,
                    workspaceConfig = WorkspaceConfigState(
                        projectsRootDir = "C:\\Users\\<User>\\Desktop",
                        defaultDesktopDir = "C:\\Users\\<User>\\Desktop"
                    ),
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
    @Config(sdk = [34], qualifiers = "w360dp-h920dp-xhdpi")
    fun capture04_HsvColorPickerDialogCard360dp() {
        val cfg = ThemeConfig(isDarkMode = true)
        renderWithThemeAndSave("04_hsv_color_picker_360dp.png", cfg) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ClawColors.Canvas),
                contentAlignment = Alignment.Center
            ) {
                HsvSectionColorPickerCard(
                    section = ThemeSectionKey.USER_BUBBLE,
                    initialColor = Color(0xFFEC4899),
                    defaultColor = Color(0xFF234177),
                    baseConfig = cfg,
                    onConfirmColor = {},
                    onResetSection = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun verifyOneTapDayNightSwitchAndSectionColorPickerFlow() {
        var themeCfg by mutableStateOf(ThemeConfig(isDarkMode = true))

        composeRule.setContent {
            ClawBridgeTheme(themeConfig = themeCfg) {
                ClawBridgeRootShell(
                    currentTab = 2,
                    onTabSelected = {},
                    connState = onlineConn(),
                    tierCode = "ULTRA",
                    sessionCount = 4,
                    isDarkMode = themeCfg.isDarkMode,
                    onToggleDarkMode = { themeCfg = themeCfg.copy(isDarkMode = !themeCfg.isDarkMode) },
                    onTopRefresh = {},
                ) {
                    SettingsScreenContent(
                        appSettings = AppSettings(themeConfig = themeCfg),
                        connState = onlineConn(),
                        isWhitelisted = true,
                        onSetDarkMode = { isDark -> themeCfg = themeCfg.copy(isDarkMode = isDark) },
                        onUpdateSectionColor = { section, argb ->
                            themeCfg = if (themeCfg.isDarkMode) {
                                themeCfg.copy(darkOverrides = themeCfg.darkOverrides.withColor(section, argb))
                            } else {
                                themeCfg.copy(lightOverrides = themeCfg.lightOverrides.withColor(section, argb))
                            }
                        },
                        onApplyThemePreset = { ov ->
                            themeCfg = if (themeCfg.isDarkMode) themeCfg.copy(darkOverrides = ov)
                            else themeCfg.copy(lightOverrides = ov)
                        },
                        onResetThemeColors = {
                            themeCfg = if (themeCfg.isDarkMode) themeCfg.copy(darkOverrides = SectionColorOverrides())
                            else themeCfg.copy(lightOverrides = SectionColorOverrides())
                        },
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
        composeRule.waitForIdle()
        assertTrue(ClawColors.isDark)

        // 1. One-tap TopBar sun button switches to Daylight Mode immediately
        composeRule.onNodeWithTag("btn_quick_theme_toggle").performClick()
        composeRule.waitForIdle()
        assertFalse(themeCfg.isDarkMode)
        assertFalse(ClawColors.isDark)
        assertEquals(0xFFF2F5FC.toInt(), ClawColors.Canvas.toArgb())

        // 2. Tap "黑夜模式" in Settings card -> switches back to Dark Mode
        composeRule.onNodeWithTag("btn_mode_dark").performClick()
        composeRule.waitForIdle()
        assertTrue(themeCfg.isDarkMode)
        assertTrue(ClawColors.isDark)

        // 3. Open Color Picker for "主按钮与选中态" (primary), input #FF5C8A, confirm -> updates live!
        composeRule.onNodeWithTag("section_color_row_primary").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hsv_color_picker_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("picker_hex_input").performTextClearance()
        composeRule.onNodeWithTag("picker_hex_input").performTextInput("#FF5C8A")
        composeRule.onNodeWithTag("picker_confirm_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(0xFFFF5C8A.toInt(), ClawColors.Primary.toArgb())

        // 4. Verify lossless serialization / deserialization of custom overrides
        val encoded = themeCfg.darkOverrides.serialize()
        val decoded = SectionColorOverrides.deserialize(encoded)
        assertEquals(themeCfg.darkOverrides, decoded)

        // 5. Reset current mode colors -> restores default Aurora Primary (#6384FF)
        composeRule.onNodeWithTag("btn_reset_theme_colors").performClick()
        composeRule.waitForIdle()
        assertEquals(0xFF6384FF.toInt(), ClawColors.Primary.toArgb())
    }
}
