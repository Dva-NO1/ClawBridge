package com.clawbridge.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.clawbridge.mobile.service.RelayForegroundService
import com.clawbridge.mobile.ui.ClawBridgeMainApp
import com.clawbridge.mobile.ui.ClawBridgeTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            RelayForegroundService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RelayForegroundService.start(this)
        }

        val app = application as ClawBridgeApp
        handleNotificationDeepLink(intent)

        setContent {
            ClawBridgeTheme {
                ClawBridgeMainApp(
                    settingsStore = app.settingsStore,
                    socketClient = app.socketClient,
                    monitorRepository = app.monitorRepository,
                    isIgnoringBatteryOptimizations = { checkBatteryWhitelisted() },
                    onRequestBatteryWhitelist = { requestIgnoreBatteryOptimizations() },
                    onOpenRomAutostartSettings = { openAutostartOrAppSettings() },
                    onOpenDownloadedUri = { uriStr, mime -> openDownloadedFile(uriStr, mime) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationDeepLink(intent)
    }

    private fun handleNotificationDeepLink(intent: Intent?) {
        val app = application as ClawBridgeApp
        val aid = intent?.getStringExtra(EXTRA_APPROVAL_ID)?.takeIf { it.isNotBlank() }
        val action = intent?.getStringExtra(EXTRA_APPROVAL_ACTION)?.takeIf { it.isNotBlank() }
        val risk = intent?.getStringExtra(EXTRA_APPROVAL_RISK) ?: "high"
        if (aid != null && action != null) {
            app.monitorRepository.triggerDeepLinkApprovalPrompt(aid, risk, action)
            intent.removeExtra(EXTRA_APPROVAL_ID)
            intent.removeExtra(EXTRA_APPROVAL_ACTION)
        }

        val sid = intent?.getStringExtra(EXTRA_OPEN_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val src = intent.getStringExtra(EXTRA_OPEN_SESSION_SOURCE) ?: "antigravity"
        app.monitorRepository.openSessionById(sid, src)
        intent.removeExtra(EXTRA_OPEN_SESSION_ID)
    }

    private fun checkBatteryWhitelisted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /**
     * Jumps directly to Xiaomi HyperOS / MIUI AutoStart & Background permissions,
     * falling back cleanly to App Details settings on other ROMs.
     */
    private fun openAutostartOrAppSettings() {
        val miuiIntents = listOf(
            Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
        for (candidate in miuiIntents) {
            if (runCatching { startActivity(candidate) }.isSuccess) return
        }
    }

    private fun openDownloadedFile(uriStr: String, mime: String?) {
        runCatching {
            val uri = Uri.parse(uriStr)
            val isApk = mime == "application/vnd.android.package-archive" ||
                uriStr.lowercase().endsWith(".apk")
            if (isApk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(permIntent)
                return
            }
            val effectiveMime = if (isApk) "application/vnd.android.package-archive" else (mime ?: "*/*")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (isApk) {
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, "打开已下载文件"))
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "extra_open_session_id"
        const val EXTRA_OPEN_SESSION_SOURCE = "extra_open_session_source"
        const val EXTRA_APPROVAL_ID = "extra_approval_id"
        const val EXTRA_APPROVAL_RISK = "extra_approval_risk"
        const val EXTRA_APPROVAL_ACTION = "extra_approval_action"
    }
}
