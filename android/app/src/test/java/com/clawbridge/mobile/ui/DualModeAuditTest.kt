package com.clawbridge.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.clawbridge.mobile.data.LanCredentialStore
import com.clawbridge.mobile.data.QuotaBucketUi
import com.clawbridge.mobile.data.QuotaDashboardState
import com.clawbridge.mobile.data.QuotaGroupCardUi
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.net.ConnectionMode
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class DualModeAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File by lazy {
        File("build/ui_screenshots_dualmode").apply {
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
    fun verifyLanCredentialStoreEncryptionAndHostParser() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = LanCredentialStore(ctx)
        val rawSecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

        store.saveLanPairing(
            lanToken = rawSecret,
            bridgeDeviceId = "bridge-pc-01",
            bridgeDeviceName = "DESKTOP-CLAWBRIDGE",
            connectedIp = "192.168.1.100",
            connectedPort = 18791,
        )

        // Verify raw SharedPreferences does NOT store plaintext lanToken
        val prefs = ctx.getSharedPreferences("clawbridge_lan_secure_v1", Context.MODE_PRIVATE)
        val storedCipher = prefs.getString("enc_lan_token", "") ?: ""
        assertTrue(storedCipher.isNotBlank())
        assertFalse("Ciphertext must NEVER contain plaintext lanToken", storedCipher.contains(rawSecret))

        // Verify decryption round-trip & snapshot
        assertEquals(rawSecret, store.getLanToken())
        val snap = store.getSnapshot()
        assertTrue(snap.isPaired)
        assertEquals("DESKTOP-CLAWBRIDGE", snap.bridgeDeviceName)
        assertEquals("192.168.1.100", snap.knownIp)
        assertEquals(18791, snap.knownPort)

        // Verify clearLanPairing wipes token while keeping knownIp for fast re-discovery
        store.clearLanPairing(keepKnownIp = true)
        assertEquals("", store.getLanToken())
        assertFalse(store.getSnapshot().isPaired)
        assertEquals("192.168.1.100", store.getSnapshot().knownIp)

        // Verify host:port parser
        assertEquals("192.168.1.100" to 18791, LanCredentialStore.parseHostAndPort("192.168.1.100"))
        assertEquals("192.168.1.8" to 18799, LanCredentialStore.parseHostAndPort("ws://192.168.1.8:18799/"))
    }

    @Test
    fun verifyTopSwitchHeightMinimum56dpAndModeSwitching() {
        var switchedTo: ConnectionMode? = null
        val lanConn = ConnectionUiState(
            connectionMode = ConnectionMode.LAN,
            socketStatus = SocketStatus.CONNECTED,
            paired = true,
            desktopOnline = true,
            latencyMs = 2L,
            lanConnectedHost = "192.168.1.100:18791",
            lanPairedDeviceName = "DESKTOP-CLAWBRIDGE",
            networkTypeLabel = "Wi-Fi 局域网",
        )
        val sessions = listOf(
            SessionSummaryUi(
                id = "s-lan-1",
                source = "antigravity",
                title = "ClawBridge 双模式内网直连联调",
                project = "ClawBridge",
                workspacePath = "C:/Projects/ClawBridge",
                preview = "局域网 WebSocket 直连延迟 2ms，双向收发正常",
                status = "running",
                stepCount = 16,
                subagentCount = 0,
                runningSubagentCount = 0,
                updatedAt = System.currentTimeMillis() - 15_000L,
                currentModelId = "m1",
                currentModelLabel = "Gemini 4 Carbon",
            )
        )

        renderAndSave("01_dualmode_lan_online_sessions_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 1,
                onTabSelected = {},
                connState = lanConn,
                tierCode = "ULTRA",
                sessionCount = sessions.size,
                onTopRefresh = {},
                onSwitchMode = { switchedTo = it },
            ) {
                SessionsScreen(
                    connState = lanConn,
                    sessions = sessions,
                    pinnedIds = setOf("s-lan-1"),
                    onRefresh = {},
                    onTogglePin = {},
                    onOpenSession = {},
                )
            }
        }

        val bounds = composeRule.onNodeWithTag("dual_mode_top_bar").getUnclippedBoundsInRoot()
        assertTrue("Top dual-mode switch bar height (${bounds.height}) must be >= 56dp", bounds.height >= 56.dp)
        composeRule.onNodeWithText("已连接 · 2ms").assertIsDisplayed()

        // Tap WAN segment -> triggers explicit switch to WAN
        composeRule.onNodeWithTag("mode_switch_wan").performClick()
        composeRule.waitForIdle()
        assertEquals(ConnectionMode.WAN, switchedTo)
    }

    @Test
    fun verifyLanPairingGuideCard360dp() {
        var submittedCode: String? = null
        val lanUnpaired = ConnectionUiState(
            connectionMode = ConnectionMode.LAN,
            socketStatus = SocketStatus.CONNECTED,
            paired = false,
            desktopOnline = false,
            lanConnectedHost = "192.168.1.100:18791",
            lanDiscoveredDeviceName = "DESKTOP-CLAWBRIDGE",
            networkTypeLabel = "Wi-Fi 局域网",
        )

        renderAndSave("02_dualmode_lan_pairing_home_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = lanUnpaired,
                tierCode = "ULTRA",
                sessionCount = 4,
                onTopRefresh = {},
            ) {
                HomeScreen(
                    connState = lanUnpaired,
                    quotaState = QuotaDashboardState(planName = "Google AI Ultra", tierCode = "ULTRA"),
                    pendingApprovalCount = 0,
                    pendingApprovals = emptyList(),
                    hasPinConfigured = true,
                    onApproveItem = {},
                    onRejectItem = {},
                    onPairSubmit = { submittedCode = it },
                    onPingOrReconnect = {},
                    onOpenSessions = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("lan_pairing_guide_card").assertIsDisplayed()
        composeRule.onNodeWithTag("lan_pair_code_input").performTextInput("805377")
        composeRule.onNodeWithTag("btn_lan_pair_submit").performClick()
        composeRule.waitForIdle()
        assertEquals("805377", submittedCode)
    }

    @Test
    fun verifyLanMismatchPromptManualIpAndOneTapWanSwitch360dp() {
        var switchedBackToWan = false
        var manualHostSubmitted: String? = null

        val mismatchState = ConnectionUiState(
            connectionMode = ConnectionMode.LAN,
            socketStatus = SocketStatus.ERROR,
            paired = true,
            desktopOnline = false,
            lanDiscoveryFailed = true,
            lanServerOffSuspected = true,
            lastError = "未发现电脑，请确认同一 WiFi 或手动输入 IP",
        )

        renderAndSave("03_dualmode_lan_mismatch_360dp.png") {
            ClawBridgeRootShell(
                currentTab = 0,
                onTabSelected = {},
                connState = mismatchState,
                tierCode = "ULTRA",
                sessionCount = 4,
                onTopRefresh = {},
                onSwitchMode = { if (it == ConnectionMode.WAN) switchedBackToWan = true },
            ) {
                HomeScreen(
                    connState = mismatchState,
                    quotaState = QuotaDashboardState(
                        available = true,
                        planName = "Google AI Ultra",
                        tierCode = "ULTRA",
                        gemini = QuotaGroupCardUi(
                            title = "Gemini",
                            description = "",
                            window5h = QuotaBucketUi("g5", "5h", 91, 9, 0.91f, null),
                            window7d = QuotaBucketUi("g7", "7d", 85, 15, 0.85f, null),
                        )
                    ),
                    pendingApprovalCount = 0,
                    pendingApprovals = emptyList(),
                    hasPinConfigured = true,
                    onApproveItem = {},
                    onRejectItem = {},
                    onPairSubmit = {},
                    onPingOrReconnect = {},
                    onOpenSessions = {},
                    onOpenSettings = {},
                    onSwitchMode = { if (it == ConnectionMode.WAN) switchedBackToWan = true },
                    onManualLanConnect = { manualHostSubmitted = it },
                )
            }
        }

        composeRule.onNodeWithText("电脑端未开启内网模式，请在电脑端切换").assertIsDisplayed()
        composeRule.onNodeWithText("未发现电脑，请确认同一 WiFi 或手动输入 IP。").assertIsDisplayed()

        // Verify manual IP fallback input & submit
        composeRule.onNodeWithTag("lan_manual_ip_input").performTextClearance()
        composeRule.onNodeWithTag("lan_manual_ip_input").performTextInput("192.168.1.100")
        composeRule.onNodeWithTag("btn_lan_manual_connect").performClick()
        composeRule.waitForIdle()
        assertEquals("192.168.1.100", manualHostSubmitted)

        // Verify explicit one-tap switch back to WAN (never silent auto-switch)
        assertFalse(switchedBackToWan)
        composeRule.onNodeWithTag("btn_switch_back_wan").performClick()
        composeRule.waitForIdle()
        assertNotNull(manualHostSubmitted)
        assertTrue(switchedBackToWan)
    }
}
