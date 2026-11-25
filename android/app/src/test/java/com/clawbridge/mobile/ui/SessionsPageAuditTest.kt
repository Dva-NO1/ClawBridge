package com.clawbridge.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class SessionsPageAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File by lazy {
        val path = System.getProperty("clawbridge.screenshot.dir")
            ?: "build/ui_screenshots_v170"
        File(path).apply { mkdirs() }
    }

    private fun connOnline() = ConnectionUiState(
        socketStatus = SocketStatus.CONNECTED,
        relayUrl = "wss://relay.example.com",
        paired = true,
        desktopOnline = true,
        latencyMs = 24L,
        networkTypeLabel = "5G",
    )

    /**
     * Builds 56 sessions faithfully reproducing the user's real Bridge data patterns:
     * - Antigravity sessions with title == preview (duplicate subtitle)
     * - Codex sessions with generic "Codex 会话 aa110001" + "[gpt-6.2-sol]" preview
     * - Codex sessions with generic "Codex 会话 aa110002" + real assistant/user message preview
     * - Markdown-decorated titles ("# ESP32-C5 ...", "**帮我...")
     * - Running, waiting_approval, error, and 52 done sessions across Today / Yesterday / Earlier
     * - Multi-session projects + 20+ single-session projects
     */
    private fun buildRealShape56Sessions(now: Long = System.currentTimeMillis()): List<SessionSummaryUi> {
        val hour = 3600_000L
        val day = 24 * hour
        val list = mutableListOf(
            SessionSummaryUi(
                id = "11111111-1111-4111-8111-111111111111",
                source = "antigravity",
                title = "Antigravity与Codex项目开发",
                project = "SampleProject",
                workspacePath = "/workspace/sample-project",
                preview = "Antigravity与Codex项目开发",
                status = "running",
                stepCount = 48,
                subagentCount = 2,
                runningSubagentCount = 1,
                updatedAt = now - 2 * 60_000L,
                currentModelId = "gemini-4-carbon-medium",
                currentModelLabel = "Gemini 4 Carbon (Medium)",
            ),
            SessionSummaryUi(
                id = "aa110001-9912-40aa-b881-000000000001",
                source = "codex",
                title = "Codex 会话 aa110001",
                project = "SampleProject",
                workspacePath = "/workspace/sample-project",
                preview = "[gpt-6.2-sol]",
                status = "waiting_approval",
                stepCount = 14,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 8 * 60_000L,
                currentModelId = "gpt-6.2-sol",
                currentModelLabel = "gpt-6.2-sol",
            ),
            SessionSummaryUi(
                id = "aa110002-88aa-4421-9c12-000000000002",
                source = "codex",
                title = "Codex 会话 aa110002",
                project = "DemoApp",
                workspacePath = "/workspace/demo-app",
                preview = "只读审查发现两处实质问题，已发送具体修复建议：1. **轴心螺栓反向旋转**：drawCrank 纵坐标应为 cy - 37*cos",
                status = "done",
                stepCount = 22,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 35 * 60_000L,
                currentModelId = "gpt-6.2-astra",
                currentModelLabel = "gpt-6.2-astra",
            ),
            SessionSummaryUi(
                id = "22222222-2222-4222-8222-222222222222",
                source = "antigravity",
                title = "ClawBridge投递实测会话",
                project = "未分组对话",
                workspacePath = "",
                preview = "ClawBridge投递实测会话",
                status = "done",
                stepCount = 25,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 2 * hour,
                currentModelId = "gemini-4-argon-medium",
                currentModelLabel = "Gemini 4 Argon (Medium)",
            ),
            SessionSummaryUi(
                id = "12760fbd-33aa-44bb-88cc-000000000004",
                source = "antigravity",
                title = "Four-Stroke Engine HTML Simulation",
                project = "内燃机",
                workspacePath = "c:/work/内燃机",
                preview = "Four-Stroke Engine HTML Simulation",
                status = "done",
                stepCount = 19,
                subagentCount = 1,
                runningSubagentCount = 0,
                updatedAt = now - day - 2 * hour,
                currentModelId = "claude-5.5-sonnet",
                currentModelLabel = "Claude 5.5 Sonnet",
            ),
            SessionSummaryUi(
                id = "01a1b07f-22aa-44bb-88cc-000000000005",
                source = "codex",
                title = "# ESP32-C5 双频 WiFi 安全实验项目：完整开发交接提示词",
                project = "ESP32",
                workspacePath = "/workspace/esp32-demo",
                preview = "已写好 [项目交接.md](//workspace/esp32-demo/项目交接.md)，记录了工程入口和实板日志。",
                status = "done",
                stepCount = 31,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - day - 4 * hour,
                currentModelId = "gpt-6.2-sol",
                currentModelLabel = "gpt-6.2-sol",
            ),
            SessionSummaryUi(
                id = "0ff5d2f3-22aa-44bb-88cc-000000000006",
                source = "antigravity",
                title = "泉州与宜宾街景图查找",
                project = "街景游戏",
                workspacePath = "c:/work/街景游戏",
                preview = "泉州与宜宾街景图查找",
                status = "done",
                stepCount = 11,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - day - 6 * hour,
                currentModelId = "",
                currentModelLabel = "",
            ),
            SessionSummaryUi(
                id = "01a1dd5f-22aa-44bb-88cc-000000000007",
                source = "codex",
                title = "**帮我电脑上的 deepseek harness 装上 [dsh-purge](https://github.com/mm)",
                project = "dsh-purge-x20",
                workspacePath = "c:/work/dsh-purge-x20",
                preview = "已完成环境安装并验证 CLI 入口可用。",
                status = "running",
                stepCount = 9,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 5 * 60_000L,
                currentModelId = "gpt-5.4",
                currentModelLabel = "gpt-5.4",
            ),
            SessionSummaryUi(
                id = "01a1b021-22aa-44bb-88cc-000000000008",
                source = "codex",
                title = "请帮我修改示例自动化测试脚本项目（基于 Python + OpenCV + ADB）",
                project = "ESP32",
                workspacePath = "c:/work/ESP32",
                preview = "已调整模板匹配阈值与技能释放间隔。",
                status = "done",
                stepCount = 15,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 3 * day,
                currentModelId = "gpt-6.2-sol",
                currentModelLabel = "gpt-6.2-sol",
            ),
            SessionSummaryUi(
                id = "57d8f055-22aa-44bb-88cc-000000000009",
                source = "antigravity",
                title = "数字小鸽子月夜房间前端应用",
                project = "数字小鸽子",
                workspacePath = "c:/work/数字小鸽子",
                preview = "数字小鸽子月夜房间前端应用",
                status = "error",
                stepCount = 17,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = now - 3 * day - hour,
                currentModelId = "gemini-4-neon",
                currentModelLabel = "Gemini 4 Neon",
            ),
        )

        val singleProjects = listOf(
            "school game", "gaijin", "NEON", "StudyNotes", "mbtitest", "tset", "website",
            "网络安全教育实验", "sample-automation", "magical-oppenheimer", "photo live",
            "音频降噪工具", "校园导航小程序", "论文排版", "Docker脚本", "蓝牙调试",
            "爬虫监控", "天气看板", "Markdown工具", "键盘宏配置", "备忘录同步",
            "家庭NAS备份", "简历优化", "股票提醒"
        )
        val multiProjects = listOf("SampleProject", "ESP32", "Relay 运维")

        // Fill up to exactly 56 sessions
        var idx = 0
        while (list.size < 56) {
            val isCodex = idx % 3 != 0
            val hex = String.format("%08x", 0x01a30000 + idx)
            val proj = if (idx < 9) multiProjects[idx % multiProjects.size] else singleProjects[(idx - 9) % singleProjects.size]
            val ageMs = (3 + idx / 3) * day + (idx % 12) * hour
            val session = if (isCodex && idx % 2 == 0) {
                SessionSummaryUi(
                    id = "$hex-0000-4000-8000-0000000000$idx",
                    source = "codex",
                    title = "Codex 会话 $hex",
                    project = proj,
                    workspacePath = "c:/work/$proj",
                    preview = "[gpt-6.2-sol]",
                    status = "done",
                    stepCount = 6 + (idx % 18),
                    subagentCount = 0,
                    runningSubagentCount = 0,
                    updatedAt = now - ageMs,
                    currentModelId = "gpt-6.2-sol",
                    currentModelLabel = "gpt-6.2-sol",
                )
            } else if (isCodex) {
                SessionSummaryUi(
                    id = "$hex-0000-4000-8000-0000000000$idx",
                    source = "codex",
                    title = "Codex 会话 $hex",
                    project = proj,
                    workspacePath = "c:/work/$proj",
                    preview = "已完成第 ${idx + 1} 项任务重构并通过单元测试验证。",
                    status = "done",
                    stepCount = 10 + idx,
                    subagentCount = 0,
                    runningSubagentCount = 0,
                    updatedAt = now - ageMs,
                    currentModelId = "gpt-6.2-astra",
                    currentModelLabel = "gpt-6.2-astra",
                )
            } else {
                val agyTitle = "${proj} 核心模块排查与修复 #$idx"
                SessionSummaryUi(
                    id = "$hex-0000-4000-8000-0000000000$idx",
                    source = "antigravity",
                    title = agyTitle,
                    project = proj,
                    workspacePath = "c:/work/$proj",
                    preview = agyTitle, // deliberately mirrors real duplicate preview
                    status = "done",
                    stepCount = 12 + idx,
                    subagentCount = if (idx % 4 == 0) 1 else 0,
                    runningSubagentCount = 0,
                    updatedAt = now - ageMs,
                    currentModelId = "gemini-4-carbon-medium",
                    currentModelLabel = "Gemini 4 Carbon (Medium)",
                )
            }
            list += session
            idx++
        }
        return list
    }

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
    fun verifyPresentationInvariantsOn56RealShapeSessions() {
        val sessions = buildRealShape56Sessions()
        assertEquals(56, sessions.size)

        val models = sessions.map { buildSessionDisplayModel(it) }

        // 1. No title == preview duplication anywhere across all 56 sessions
        for (m in models) {
            if (m.previewText.isNotBlank()) {
                assertNotEquals(
                    "Preview must not duplicate title for ${m.raw.id}",
                    cleanCompactText(m.displayTitle),
                    cleanCompactText(m.previewText)
                )
            }
        }

        // 2. Generic ID title with real preview ("Codex 会话 aa110002") translates to human title & preserves #aa110002
        val m066b = models.first { it.raw.id.startsWith("aa110002") }
        assertTrue(m066b.isGenericIdTranslated)
        assertFalse(m066b.displayTitle.startsWith("Codex 会话"))
        assertTrue(m066b.displayTitle.contains("只读审查"))
        assertTrue(m066b.previewText.contains("#aa110002"))
        assertFalse(m066b.previewText.startsWith("只读审查发现两处"))

        // 3. Generic ID title with ONLY "[gpt-6.2-sol]" preview extracts model chip & humanizes via project
        val m0cb6 = models.first { it.raw.id.startsWith("aa110001") }
        assertTrue(m0cb6.isGenericIdTranslated)
        assertEquals("gpt-6.2-sol", m0cb6.modelTag)
        assertTrue(m0cb6.displayTitle.contains("SampleProject"))
        assertTrue(m0cb6.previewText.contains("#aa110001"))

        // 4. Antigravity duplicate title==preview replaces line 2 with non-duplicate step metadata
        val mAgy = models.first { it.raw.id.startsWith("11111111") }
        assertEquals("Antigravity与Codex项目开发", mAgy.displayTitle)
        assertTrue(mAgy.previewText.contains("48 步"))
        assertFalse(mAgy.previewText.contains("Antigravity与Codex项目开发"))

        // 5. Search covers title, project, model, preview message, and id
        assertTrue(matchesSessionFilters(m066b, "all", SessionStatusFilter.ALL, "轴心螺栓"))
        assertTrue(matchesSessionFilters(m066b, "all", SessionStatusFilter.ALL, "DemoApp"))
        assertTrue(matchesSessionFilters(m066b, "all", SessionStatusFilter.ALL, "gpt-6.2-astra"))
        assertTrue(matchesSessionFilters(m0cb6, "all", SessionStatusFilter.ALL, "gpt-6.2-sol"))
        assertTrue(matchesSessionFilters(m0cb6, "all", SessionStatusFilter.ALL, "aa110001"))
        assertTrue(matchesSessionFilters(mAgy, "all", SessionStatusFilter.ALL, "SampleProject gemini"))

        // 6. Project view: single-session groups produce ZERO standalone CollapsibleProjectHeader rows
        val projectEntries = buildSessionListEntries(
            filtered = models,
            pinnedIds = emptySet(),
            viewMode = SessionViewMode.PROJECT,
            expandedProjects = emptyMap(),
            searchActive = false
        )
        val headers = projectEntries.filterIsInstance<SessionListEntry.CollapsibleProjectHeader>()
        assertTrue(headers.isNotEmpty())
        assertTrue("No single-session group may have a standalone header", headers.all { it.count >= 2 })
    }

    @Test
    fun capture01_SessionsTimeView56_360dp() {
        val sessions = buildRealShape56Sessions()
        renderAndSave("01_sessions_time_56_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = connOnline(),
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
            ) {
                SessionsScreen(
                    connState = connOnline(),
                    sessions = sessions,
                    pinnedIds = setOf("11111111-1111-4111-8111-111111111111"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                    initialViewMode = SessionViewMode.TIME,
                )
            }
        }
    }

    @Test
    fun capture02_SessionsProjectView56_360dp() {
        val sessions = buildRealShape56Sessions()
        renderAndSave("02_sessions_project_56_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = connOnline(),
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
            ) {
                SessionsScreen(
                    connState = connOnline(),
                    sessions = sessions,
                    pinnedIds = setOf("11111111-1111-4111-8111-111111111111"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                    initialViewMode = SessionViewMode.PROJECT,
                )
            }
        }
    }

    @Test
    fun capture03_SessionsSearchActive_360dp() {
        val sessions = buildRealShape56Sessions()
        renderAndSave("03_sessions_search_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = connOnline(),
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
            ) {
                SessionsScreen(
                    connState = connOnline(),
                    sessions = sessions,
                    pinnedIds = setOf("11111111-1111-4111-8111-111111111111"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                    initialViewMode = SessionViewMode.TIME,
                    initialSearchQuery = "ESP32",
                )
            }
        }
    }

    @Test
    fun verifyInteractiveSearchViewSwitchStatusPinOpenAnd56Scroll() {
        val sessions = buildRealShape56Sessions()
        var openedSession: SessionSummaryUi? = null
        var pinnedState by mutableStateOf(setOf("11111111-1111-4111-8111-111111111111"))

        composeRule.setContent {
            ClawBridgeTheme {
                SessionsScreen(
                    connState = connOnline(),
                    sessions = sessions,
                    pinnedIds = pinnedState,
                    onRefresh = {},
                    onTogglePin = { id ->
                        pinnedState = if (id in pinnedState) pinnedState - id else pinnedState + id
                    },
                    onOpenSession = { openedSession = it },
                )
            }
        }
        composeRule.waitForIdle()

        // 1. Scroll across all 56+ items smoothly in Time View
        composeRule.onNodeWithTag("sessions_lazy_list").performScrollToIndex(50)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sessions_lazy_list").performScrollToIndex(0)
        composeRule.waitForIdle()

        // 2. Click a session -> opens exact session
        composeRule.onNodeWithTag("session_row_antigravity_11111111-1111-4111-8111-111111111111").performClick()
        composeRule.waitForIdle()
        assertEquals("11111111-1111-4111-8111-111111111111", openedSession?.id)

        // 3. Long-press an unpinned session -> Pin via dialog -> pinnedState updates
        val targetId = "aa110001-9912-40aa-b881-000000000001"
        assertFalse(targetId in pinnedState)
        composeRule.onNodeWithTag("session_row_codex_$targetId").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("置顶会话").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(targetId in pinnedState)

        // 4. Switch to Project View and toggle a collapsible multi-session project group
        composeRule.onNodeWithTag("view_mode_project").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("project_group_ESP32").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 5. Switch back to Time View & test Status filter ("进行中")
        composeRule.onNodeWithTag("view_mode_time").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("filter_status_running").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Antigravity与Codex项目开发").fetchSemanticsNodes().isNotEmpty())

        // Reset status filter to ALL
        composeRule.onNodeWithTag("filter_status_all").performClick()
        composeRule.waitForIdle()

        // 6. Search by preview text ("轴心螺栓") -> filters accurately & clear button resets
        composeRule.onNodeWithTag("session_search_input").performTextInput("轴心螺栓")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("匹配 1 / 56 个会话").assertIsDisplayed()
        composeRule.onNodeWithTag("session_search_clear").performClick()
        composeRule.waitForIdle()
    }
}
