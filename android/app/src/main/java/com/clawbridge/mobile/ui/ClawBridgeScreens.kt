package com.clawbridge.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.CodexOpenAiQuotaUi
import com.clawbridge.mobile.data.LanPairingSnapshot
import com.clawbridge.mobile.data.ModelOptionUi
import com.clawbridge.mobile.data.MonitorRepository
import com.clawbridge.mobile.data.PendingApprovalUi
import com.clawbridge.mobile.data.ProjectCreationState
import com.clawbridge.mobile.data.QuotaBucketUi
import com.clawbridge.mobile.data.QuotaDashboardState
import com.clawbridge.mobile.data.QuotaGroupCardUi
import com.clawbridge.mobile.data.SessionSummaryUi
import com.clawbridge.mobile.data.SettingsStore
import com.clawbridge.mobile.data.WorkspaceConfigState
import com.clawbridge.mobile.net.ConnectionMode
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.RelaySocketClient
import com.clawbridge.mobile.net.SocketStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClawBridgeMainApp(
    settingsStore: SettingsStore,
    socketClient: RelaySocketClient,
    monitorRepository: MonitorRepository,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenRomAutostartSettings: () -> Unit,
    onOpenDownloadedUri: (String, String?) -> Unit,
) {
    val connState by socketClient.uiState.collectAsState()
    val appSettings by settingsStore.settingsFlow.collectAsState(initial = AppSettings())
    val quotaState by monitorRepository.quota.collectAsState()
    val sessions by monitorRepository.sessions.collectAsState()
    val activeDetail by monitorRepository.activeDetail.collectAsState()
    val activeMetrics by monitorRepository.activeMetrics.collectAsState()
    val previewState by monitorRepository.preview.collectAsState()
    val downloadStatus by monitorRepository.downloadStatus.collectAsState()
    val uploadStatus by monitorRepository.uploadStatus.collectAsState()
    val pendingApprovals by monitorRepository.pendingApprovals.collectAsState()
    val actionFeedback by monitorRepository.actionFeedback.collectAsState()
    val deepLinkPrompt by monitorRepository.deepLinkApprovalPrompt.collectAsState()
    val workspaceConfig by monitorRepository.workspaceConfig.collectAsState()
    val projectCreation by monitorRepository.projectCreation.collectAsState()
    val antigravityModels by monitorRepository.antigravityModels.collectAsState()
    val codexModels by monitorRepository.codexModels.collectAsState()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var globalPinGate by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val scope = rememberCoroutineScope()
    val desktopOnline = connState.paired && connState.desktopOnline

    LaunchedEffect(workspaceConfig.projectsRootDir) {
        if (workspaceConfig.projectsRootDir.isNotBlank() && workspaceConfig.projectsRootDir != appSettings.projectsRootDir) {
            settingsStore.saveCachedProjectsRootDir(workspaceConfig.projectsRootDir)
        }
    }

    // Handle notification action deep-links ("进App输PIN批准" / "拒绝(进App)")
    LaunchedEffect(deepLinkPrompt) {
        val prompt = deepLinkPrompt ?: return@LaunchedEffect
        val (aid, risk, action) = prompt
        monitorRepository.clearDeepLinkApprovalPrompt()
        val decision = if (action == "reject") "reject" else "approve"
        if (risk == "high" || decision == "reject") {
            globalPinGate = Triple(
                if (decision == "approve") "高风险操作审批确认" else "确认拒绝操作",
                if (decision == "approve") "该命令涉及高风险操作，请输入安全 PIN 批准：" else "请输入安全 PIN 确认拒绝该操作："
            ) {
                monitorRepository.respondApproval(aid, "", decision, true)
            }
        } else {
            monitorRepository.respondApproval(aid, "", decision, false)
        }
    }

    val activeGlobalPin = globalPinGate
    if (activeGlobalPin != null) {
        PinVerifyOrSetupDialog(
            hasPinConfigured = appSettings.hasPin,
            promptTitle = activeGlobalPin.first,
            promptSubtitle = activeGlobalPin.second,
            settingsStore = settingsStore,
            onVerified = {
                val cb = activeGlobalPin.third
                globalPinGate = null
                cb()
            },
            onDismiss = { globalPinGate = null }
        )
    }

    // Full-screen Session Detail view when a session is open
    val detail = activeDetail
    if (detail != null) {
        val sessApprovals = remember(pendingApprovals, detail.session.id) {
            pendingApprovals.filter { it.sessionId == detail.session.id || it.sessionId.isBlank() }
        }
        SessionDetailScreen(
            detail = detail,
            metrics = activeMetrics,
            appSettings = appSettings,
            settingsStore = settingsStore,
            desktopOnline = desktopOnline,
            sessionApprovals = sessApprovals,
            actionFeedback = actionFeedback,
            previewState = previewState,
            downloadStatus = downloadStatus,
            uploadStatus = uploadStatus,
            onBack = { monitorRepository.closeActiveSession() },
            onRefresh = { monitorRepository.openSession(detail.session) },
            onSelectModel = { model ->
                monitorRepository.setSessionModel(detail.session.id, detail.session.source, model.modelId, model.label)
            },
            onRefreshModels = { monitorRepository.refreshModels(force = true) },
            onSendChat = { txt -> monitorRepository.sendChatMessage(detail.session.id, detail.session.source, txt) },
            onSendQuickCommand = { key, label -> monitorRepository.sendQuickCommand(detail.session.id, detail.session.source, key, label) },
            onInspectUri = { uri -> monitorRepository.inspectUriMetadata(uri) },
            onUploadAttachment = { att, deliverToChat, promptText ->
                monitorRepository.uploadAttachmentToDesktop(
                    sessionId = detail.session.id,
                    source = detail.session.source,
                    attachment = att,
                    deliverToChat = deliverToChat,
                    promptText = promptText
                )
            },
            onClearUploadBanner = { monitorRepository.clearUploadBanner() },
            onRespondApproval = { appr, decision, pinOk -> monitorRepository.respondApproval(appr.approvalId, appr.sessionId, decision, pinOk) },
            onClearActionFeedback = { monitorRepository.clearActionFeedback() },
            onPreviewArtifact = { item -> monitorRepository.requestArtifactPreview(detail.session.id, item) },
            onDismissPreview = { monitorRepository.dismissPreview() },
            onDownloadArtifact = { artId, name -> monitorRepository.requestArtifactDownload(detail.session.id, artId, name) },
            onDirectFileDownload = { filePath, displayName ->
                monitorRepository.requestDirectFileDownload(detail.session.id, filePath, displayName)
            },
            onClearDownloadBanner = { monitorRepository.clearDownloadBanner() },
            onOpenDownloadedUri = onOpenDownloadedUri,
            isDarkMode = appSettings.themeConfig.isDarkMode,
            onToggleDarkMode = { scope.launch { settingsStore.toggleDarkMode() } },
        )
        return
    }

    val pendingApprovalCount = remember(sessions, pendingApprovals) {
        maxOf(pendingApprovals.size, sessions.count { it.status == "waiting_approval" })
    }
    val lanSnapshot = remember(connState) { socketClient.getLanPairingSnapshot() }

    ClawBridgeRootShell(
        currentTab = currentTab,
        onTabSelected = { currentTab = it },
        connState = connState,
        tierCode = quotaState.tierCode,
        sessionCount = sessions.size,
        isDarkMode = appSettings.themeConfig.isDarkMode,
        onToggleDarkMode = { scope.launch { settingsStore.toggleDarkMode() } },
        onSwitchMode = { mode -> socketClient.switchMode(mode) },
        onTopRefresh = {
            if (connState.socketStatus == SocketStatus.CONNECTED && connState.paired) {
                socketClient.sendPingNow()
                if (connState.desktopOnline) {
                    monitorRepository.refreshQuota(force = true)
                    monitorRepository.refreshSessions()
                }
            } else {
                socketClient.reconnectNow()
            }
        }
    ) {
        when (currentTab) {
            0 -> HomeScreen(
                connState = connState,
                quotaState = quotaState,
                pendingApprovalCount = pendingApprovalCount,
                pendingApprovals = pendingApprovals,
                hasPinConfigured = appSettings.hasPin,
                onApproveItem = { appr ->
                    if (appr.riskLevel == "high") {
                        globalPinGate = Triple(
                            "高风险操作审批确认",
                            "即将批准高风险命令：\n${appr.commandPreview.take(120)}"
                        ) {
                            monitorRepository.respondApproval(appr.approvalId, appr.sessionId, "approve", true)
                        }
                    } else {
                        monitorRepository.respondApproval(appr.approvalId, appr.sessionId, "approve", false)
                    }
                },
                onRejectItem = { appr ->
                    if (appSettings.hasPin) {
                        globalPinGate = Triple(
                            "确认拒绝操作",
                            "请输入安全 PIN 确认拒绝该操作"
                        ) {
                            monitorRepository.respondApproval(appr.approvalId, appr.sessionId, "reject", true)
                        }
                    } else {
                        monitorRepository.respondApproval(appr.approvalId, appr.sessionId, "reject", false)
                    }
                },
                onPairSubmit = { socketClient.submitPairingCode(it) },
                onPingOrReconnect = {
                    if (connState.socketStatus == SocketStatus.CONNECTED && connState.paired) {
                        socketClient.sendPingNow()
                        monitorRepository.refreshQuota(force = true)
                    } else {
                        socketClient.reconnectNow()
                    }
                },
                onSwitchMode = { mode -> socketClient.switchMode(mode) },
                onManualLanConnect = { host -> socketClient.connectLanManualHost(host) },
                onOpenSessions = { currentTab = 1 },
                onOpenSettings = { currentTab = 2 }
            )

            1 -> SessionsScreen(
                connState = connState,
                sessions = sessions,
                pinnedIds = appSettings.pinnedSessionIds,
                onRefresh = { monitorRepository.refreshSessions() },
                onTogglePin = { sid -> scope.launch { settingsStore.togglePinSession(sid) } },
                onOpenSession = { summary -> monitorRepository.openSession(summary) },
                workspaceConfig = workspaceConfig,
                projectCreation = projectCreation,
                defaultProjectsRootHint = appSettings.projectsRootDir,
                antigravityModels = antigravityModels,
                codexModels = codexModels,
                onCreateProject = { name, src, modelId, modelLabel, prompt ->
                    monitorRepository.createNewProject(name, src, modelId, modelLabel, prompt)
                },
                onClearProjectError = { monitorRepository.clearProjectCreationError() },
                onRefreshModels = { monitorRepository.refreshModels(force = true) },
                onOpenSettings = { currentTab = 2 },
                onSwitchMode = { mode -> socketClient.switchMode(mode) },
                onPairSubmit = { code -> socketClient.submitPairingCode(code) },
                onManualLanConnect = { host -> socketClient.connectLanManualHost(host) },
                onRetryDiscover = { socketClient.reconnectNow() },
            )

            2 -> SettingsScreenContent(
                appSettings = appSettings,
                connState = connState,
                isWhitelisted = isIgnoringBatteryOptimizations(),
                workspaceConfig = workspaceConfig,
                lanSnapshot = lanSnapshot,
                onConnectManualLanHost = { host ->
                    socketClient.connectLanManualHost(host)
                    currentTab = 0
                },
                onUnpairLan = {
                    socketClient.unpairLanOnly()
                },
                onSaveProjectsRootDir = { newDir ->
                    scope.launch { settingsStore.saveCachedProjectsRootDir(newDir) }
                    monitorRepository.updateProjectsRootDir(newDir, resetDefault = false)
                },
                onResetProjectsRootDir = {
                    monitorRepository.updateProjectsRootDir("", resetDefault = true)
                },
                onClearWorkspaceFeedback = { monitorRepository.clearWorkspaceConfigFeedback() },
                onSetDarkMode = { isDark ->
                    scope.launch { settingsStore.setDarkMode(isDark) }
                },
                onUpdateSectionColor = { section, argb ->
                    scope.launch {
                        settingsStore.updateSectionColor(appSettings.themeConfig.isDarkMode, section, argb)
                    }
                },
                onApplyThemePreset = { presetOverrides ->
                    scope.launch {
                        settingsStore.applyThemePreset(appSettings.themeConfig.isDarkMode, presetOverrides)
                    }
                },
                onResetThemeColors = {
                    scope.launch {
                        settingsStore.resetCurrentModeColors(appSettings.themeConfig.isDarkMode)
                    }
                },
                onSaveRelayConfig = { url, trustSelfSigned ->
                    socketClient.applyNewRelayConfig(url, trustSelfSigned)
                },
                onUnpairAndReset = { socketClient.unpairWanOnly() },
                onSavePin = { pin -> scope.launch { settingsStore.setPin(pin) } },
                onUpdatePrefs = { reqPin, notifyOn, dndOn, dndStart, dndEnd ->
                    scope.launch {
                        settingsStore.updatePreferences(
                            requirePinForSend = reqPin,
                            notificationsEnabled = notifyOn,
                            dndEnabled = dndOn,
                            dndStart = dndStart,
                            dndEnd = dndEnd
                        )
                    }
                },
                onUpdateTaskDoneNotify = { enabled ->
                    scope.launch {
                        settingsStore.updatePreferences(taskDoneNotifyEnabled = enabled)
                    }
                },
                onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                onOpenRomAutostartSettings = onOpenRomAutostartSettings,
                onNavigateHomeForPair = { currentTab = 0 }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClawBridgeRootShell(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    connState: ConnectionUiState,
    tierCode: String,
    sessionCount: Int,
    onTopRefresh: () -> Unit,
    isDarkMode: Boolean = ClawColors.isDark,
    onToggleDarkMode: () -> Unit = {},
    onSwitchMode: (ConnectionMode) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = ClawColors.Canvas,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ClawColors.ChromeBar,
                        titleContentColor = ClawColors.TextPrimary,
                        actionIconContentColor = ClawColors.TextSecondary,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ClawBridge",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                color = ClawColors.TextPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            StatusDot(connState)
                            if (tierCode.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = ClawColors.PrimarySoft,
                                    contentColor = ClawColors.SecondaryAccent,
                                    shape = ClawDimens.BadgeShape,
                                    border = BorderStroke(1.dp, ClawColors.PrimaryBorder)
                                ) {
                                    Text(
                                        text = tierCode,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onToggleDarkMode,
                            modifier = Modifier.testTag("btn_quick_theme_toggle")
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = if (isDarkMode) "切换至白天模式" else "切换至黑夜模式",
                                tint = if (isDarkMode) ClawColors.Warning else ClawColors.Primary
                            )
                        }
                        IconButton(onClick = onTopRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新数据")
                        }
                    }
                )
                HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)
                if (currentTab == 0 || currentTab == 1) {
                    DualModeTopSwitchBar(
                        connState = connState,
                        onSwitchMode = onSwitchMode
                    )
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)
                NavigationBar(
                    containerColor = ClawColors.ChromeBar,
                    tonalElevation = 0.dp
                ) {
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClawColors.Accent,
                        selectedTextColor = ClawColors.TextPrimary,
                        indicatorColor = ClawColors.PrimarySoft,
                        unselectedIconColor = ClawColors.TextMuted,
                        unselectedTextColor = ClawColors.TextMuted,
                    )
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { onTabSelected(0) },
                        colors = navColors,
                        icon = { Icon(Icons.Rounded.Dashboard, contentDescription = null) },
                        label = { Text("首页", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { onTabSelected(1) },
                        colors = navColors,
                        icon = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null) },
                        label = { Text("会话 ($sessionCount)", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { onTabSelected(2) },
                        colors = navColors,
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("设置", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (connState.protocolError != null) {
                Surface(
                    color = ClawColors.ErrorBg,
                    contentColor = ClawColors.Error,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = connState.protocolError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                HorizontalDivider(color = ClawColors.ErrorBorder)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun StatusDot(state: ConnectionUiState) {
    val color = when {
        state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline -> ClawColors.Success
        state.socketStatus == SocketStatus.CONNECTED && state.paired && !state.desktopOnline -> ClawColors.Warning
        state.socketStatus == SocketStatus.CONNECTED && !state.paired -> ClawColors.Info
        state.socketStatus == SocketStatus.CONNECTING -> ClawColors.Warning
        else -> ClawColors.Error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

// ─── Home Screen ─────────────────────────────────────────────────────────────

@Composable
internal fun HomeScreen(
    connState: ConnectionUiState,
    quotaState: QuotaDashboardState,
    pendingApprovalCount: Int,
    pendingApprovals: List<PendingApprovalUi>,
    hasPinConfigured: Boolean,
    onApproveItem: (PendingApprovalUi) -> Unit,
    onRejectItem: (PendingApprovalUi) -> Unit,
    onPairSubmit: (String) -> Unit,
    onPingOrReconnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchMode: (ConnectionMode) -> Unit = {},
    onManualLanConnect: (String) -> Unit = {},
) {
    var pairCodeInput by rememberSaveable { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isLanMode = connState.connectionMode == ConnectionMode.LAN

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ClawDimens.ScreenPadding, vertical = ClawDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(ClawDimens.SectionGap)
    ) {
        // 0. 内网模式下发现失败 / 电脑端未开启内网模式提示卡 + 一键切回外网 (P4.2 & P4.5)
        if (isLanMode && connState.socketStatus == SocketStatus.ERROR) {
            LanMismatchAndDiscoveryCard(
                connState = connState,
                onRetryDiscover = onPingOrReconnect,
                onManualConnect = onManualLanConnect,
                onSwitchToWan = { onSwitchMode(ConnectionMode.WAN) }
            )
        }

        // 1. 连接状态与通道信息主卡
        ConnectionHeroCard(
            state = connState,
            planName = quotaState.planName,
            onPingOrReconnect = onPingOrReconnect,
            onOpenSettings = onOpenSettings
        )

        // 2. 若未配对，展示对应模式的 6 位配对码输入卡片
        if (!connState.paired) {
            if (isLanMode && connState.socketStatus != SocketStatus.ERROR) {
                LanPairingGuideCard(
                    connState = connState,
                    onSubmitLanPairCode = onPairSubmit,
                    onManualConnect = onManualLanConnect
                )
            } else if (!isLanMode) {
                Card(
                    shape = ClawDimens.CardShape,
                    colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
                    border = ClawDimens.StrongBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(ClawDimens.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Link,
                                contentDescription = null,
                                tint = ClawColors.Accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "配对绑定桌面桥",
                                style = MaterialTheme.typography.titleMedium,
                                color = ClawColors.TextPrimary
                            )
                        }
                        Text(
                            text = "请输入渲染本 ClawBridge 生成的 6 位一次性配对码（10 分钟内有效）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextSecondary
                        )
                        OutlinedTextField(
                            value = pairCodeInput,
                            onValueChange = { next ->
                                pairCodeInput = next.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("6 位配对码") },
                            placeholder = { Text("例如 479494") },
                            singleLine = true,
                            shape = ClawDimens.NestedShape,
                            colors = clawTextFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!connState.pairingError.isNullOrBlank()) {
                            Text(
                                text = connState.pairingError,
                                color = ClawColors.Error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { onPairSubmit(pairCodeInput) },
                            enabled = pairCodeInput.length == 6 && !connState.pairingBusy,
                            shape = ClawDimens.NestedShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClawColors.Primary,
                                contentColor = ClawColors.OnPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (connState.pairingBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ClawColors.OnPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在验证配对...", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("立即配对", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // 3. 待确认事项入口卡片（安静克制的红点计数）
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = if (pendingApprovalCount > 0) BorderStroke(1.dp, ClawColors.ErrorBorder) else ClawDimens.HairlineBorder,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ClawDimens.CardShape)
                .clickable { onOpenSessions() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ClawDimens.CardPadding),
                horizontalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "待确认事项",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary
                    )
                    Text(
                        text = when {
                            !connState.paired || !connState.desktopOnline -> "桌面离线，所有写操作已安全锁定"
                            pendingApprovalCount > 0 -> "有 $pendingApprovalCount 个操作等待确认，点击查看"
                            else -> "当前暂无待审批操作，点击进入会话列表"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (pendingApprovalCount > 0) ClawColors.ErrorBg else ClawColors.SurfaceElevated,
                        contentColor = if (pendingApprovalCount > 0) ClawColors.Error else ClawColors.TextSecondary,
                        shape = CircleShape,
                        border = if (pendingApprovalCount > 0) BorderStroke(1.dp, ClawColors.ErrorBorder) else ClawDimens.HairlineBorder
                    ) {
                        Text(
                            text = pendingApprovalCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = ClawColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 实时待审批卡片直接展示在首页
        if (pendingApprovals.isNotEmpty()) {
            val online = connState.paired && connState.desktopOnline
            pendingApprovals.forEach { appr ->
                PendingApprovalCard(
                    item = appr,
                    desktopOnline = online,
                    onApproveClick = { onApproveItem(appr) },
                    onRejectClick = { onRejectItem(appr) }
                )
            }
        }

        // 4. 配额数据同步时间与陈旧提示
        val isStale = quotaState.isStale(nowMs)
        if (quotaState.fetchedAt != null) {
            val timeStr = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(quotaState.fetchedAt))
            if (isStale || quotaState.fromCache) {
                Surface(
                    color = ClawColors.WarningBg,
                    shape = ClawDimens.NestedShape,
                    border = BorderStroke(1.dp, ClawColors.WarningBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = ClawColors.Warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isStale) "非实时数据（超过 15 分钟未刷新）· 数据时间：$timeStr"
                            else "本地缓存数据 · 数据时间：$timeStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.Warning
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = ClawColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "配额同步于 $timeStr · ${quotaState.planName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextMuted
                    )
                }
            }
        }

        // 5A. Codex 桌面端独立配额（OpenAI）
        ClientSectionHeader(
            source = "codex",
            title = "Codex 桌面端额度",
            trailing = quotaState.codexOpenAi?.planName ?: "OpenAI"
        )
        CodexOpenAiQuotaCard(
            codexQuota = quotaState.codexOpenAi,
            nowMs = nowMs
        )

        // 5B. Antigravity 反重力客户端配额
        ClientSectionHeader(
            source = "antigravity",
            title = "Antigravity 客户端额度",
            trailing = quotaState.planName
        )
        QuotaDualWindowCard(
            title = "Gemini 模型配额",
            subtitle = "反重力 Gemini 4 / 3.8 系列共享池",
            group = quotaState.gemini,
            nowMs = nowMs
        )
        QuotaDualWindowCard(
            title = "Claude / GPT 附赠池",
            subtitle = "反重力内置 Opus / Sonnet 池，与 Codex 额度独立",
            group = quotaState.claudeGpt,
            nowMs = nowMs
        )

        Spacer(Modifier.height(ClawDimens.SpaceSm))
    }
}

@Composable
private fun ClientSectionHeader(
    source: String,
    title: String,
    trailing: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceBadge(source)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = ClawColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextMuted
            )
        }
    }
}

@Composable
private fun ConnectionHeroCard(
    state: ConnectionUiState,
    planName: String,
    onPingOrReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isLan = state.connectionMode == ConnectionMode.LAN
    val (headline, subtitle, badgeColor) = if (isLan) {
        when {
            state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline ->
                Triple(
                    "内网已直连 · 桌面在线",
                    "电脑：${state.lanPairedDeviceName.ifBlank { state.lanConnectedHost }} · 套餐：$planName",
                    ClawColors.Success
                )
            state.socketStatus == SocketStatus.CONNECTED && !state.paired ->
                Triple(
                    "已发现电脑 · 待内网配对",
                    "电脑：${state.lanDiscoveredDeviceName.ifBlank { state.lanConnectedHost }} · 请在下方输入 6 位内网配对码",
                    ClawColors.Info
                )
            state.socketStatus == SocketStatus.CONNECTING ->
                Triple(
                    "正在扫描局域网电脑...",
                    state.modeSwitchingHint ?: "正在广播发现并直连电脑 (UDP 18792)",
                    ClawColors.Warning
                )
            else ->
                Triple(
                    "内网直连未连接",
                    state.lastError ?: "电脑端未开启内网模式或不在同一 WiFi",
                    ClawColors.Error
                )
        }
    } else {
        when {
            state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline ->
                Triple("已直连 · 桌面在线", "套餐：$planName · 实时监控就绪", ClawColors.Success)
            state.socketStatus == SocketStatus.CONNECTED && state.paired && !state.desktopOnline ->
                Triple("桌面离线", "已连接中继服务器，等待渲染本上线", ClawColors.Warning)
            state.socketStatus == SocketStatus.CONNECTED && !state.paired ->
                Triple("中继已连通 · 待配对", "请在下方输入渲染本 6 位配对码", ClawColors.Info)
            state.socketStatus == SocketStatus.CONNECTING ->
                Triple("正在连接中继...", "正在建立安全 WSS 通道", ClawColors.Warning)
            else ->
                Triple("中继未连接", state.lastError ?: "断线指数退避重连中...", ClawColors.Error)
        }
    }

    val isOnlineHealthy = state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline
    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = BorderStroke(1.dp, if (isOnlineHealthy) ClawColors.PrimaryBorder else ClawColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        if (isOnlineHealthy) ClawColors.PrimaryGradientBrush
                        else Brush.horizontalGradient(listOf(badgeColor, badgeColor.copy(alpha = 0.5f)))
                    )
            )
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
            // Top Status + Latency Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ClawDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineSmall,
                        color = ClawColors.TextPrimary
                    )
                }
                if (state.latencyMs != null && state.socketStatus == SocketStatus.CONNECTED) {
                    Surface(
                        color = ClawColors.SurfaceElevated,
                        shape = ClawDimens.BadgeShape,
                        border = ClawDimens.HairlineBorder
                    ) {
                        Text(
                            text = "${state.latencyMs} ms",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = ClawColors.Accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ClawColors.TextSecondary
            )

            HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)

            // Full-width Address row — NEVER truncated with ellipsis!
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isLan) "电脑局域网直连地址" else "中继服务器地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.TextMuted
                    )
                    Text(
                        text = if (isLan) {
                            val fallbackText = if (state.socketStatus == SocketStatus.CONNECTING) "自动发现中..." else "未发现局域网主机"
                            val endpoint = state.lanConnectedHost.ifBlank {
                                state.lanKnownIp.takeIf { it.isNotBlank() }?.let { "$it:18791" } ?: fallbackText
                            }
                            if (endpoint.startsWith("ws://") || endpoint == fallbackText) endpoint else "ws://$endpoint"
                        } else {
                            state.relayUrl
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "网络状态",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.TextMuted
                    )
                    Text(
                        text = state.networkTypeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextSecondary
                    )
                }
            }

            // Action row cleanly separated at the bottom of the card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ClawDimens.SpaceSm)
            ) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isLan) "连接设置" else "中继设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClawColors.TextPrimary
                    )
                }
                FilledTonalButton(
                    onClick = onPingOrReconnect,
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ClawColors.PrimarySoft,
                        contentColor = ClawColors.TextPrimary
                    ),
                    border = BorderStroke(1.dp, ClawColors.PrimaryBorder),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when {
                            state.socketStatus == SocketStatus.CONNECTED && state.paired -> "刷新状态"
                            isLan -> "重新扫描"
                            else -> "立即重连"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun CodexOpenAiQuotaCard(
    codexQuota: CodexOpenAiQuotaUi?,
    nowMs: Long,
) {
    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = ClawDimens.HairlineBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (codexQuota == null || !codexQuota.available) {
                Text(
                    text = "等待桌面端 Codex 返回 OpenAI 额度数据...",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextMuted
                )
            } else {
                if (codexQuota.resetCreditsCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OpenAI 独立额度池",
                            style = MaterialTheme.typography.labelMedium,
                            color = ClawColors.TextSecondary
                        )
                        Surface(
                            color = ClawColors.AccentSoft,
                            shape = ClawDimens.BadgeShape,
                            border = BorderStroke(1.dp, ClawColors.AccentBorder)
                        ) {
                            Text(
                                text = "免费重置券 ${codexQuota.resetCreditsCount} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.Accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.7f), thickness = 1.dp)
                }
                QuotaBarRow(label = "5 小时额度", bucket = codexQuota.window5h, nowMs = nowMs)
                HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.55f), thickness = 1.dp)
                QuotaBarRow(label = "7 天周额度", bucket = codexQuota.window7d, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun QuotaDualWindowCard(
    title: String,
    subtitle: String,
    group: QuotaGroupCardUi?,
    nowMs: Long,
) {
    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = ClawDimens.HairlineBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Vertical Column stack guarantees zero title/subtitle overlap on all widths
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextMuted
                )
            }

            HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.7f), thickness = 1.dp)

            if (group == null) {
                Text(
                    text = "等待桌面桥返回配额数据...",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextMuted
                )
            } else {
                QuotaBarRow(label = "5 小时窗口", bucket = group.window5h, nowMs = nowMs)
                HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.55f), thickness = 1.dp)
                QuotaBarRow(label = "7 天周限额", bucket = group.window7d, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun QuotaBarRow(
    label: String,
    bucket: QuotaBucketUi?,
    nowMs: Long,
) {
    if (bucket == null) return
    // Remaining-quota fuel-gauge bar: 98% remaining -> 98% full green bar!
    val remainingPct = bucket.remainingPercent.coerceIn(0, 100)
    val remainingFrac = (remainingPct / 100f).coerceIn(0f, 1f)
    val isHealthy = remainingPct > 35
    val barColor = when {
        remainingPct <= 15 -> ClawColors.Error
        remainingPct <= 35 -> ClawColors.Warning
        else -> ClawColors.Accent
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = ClawColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "剩余 ",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.TextMuted,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
                Text(
                    text = "$remainingPct%",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    fontFamily = FontFamily.Monospace,
                    color = barColor
                )
            }
        }

        // Remaining-quota luminous Aurora mint-emerald bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(CircleShape)
                .background(ClawColors.QuotaTrack)
        ) {
            if (remainingFrac > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(remainingFrac)
                        .height(9.dp)
                        .clip(CircleShape)
                        .let { mod ->
                            if (isHealthy) mod.background(ClawColors.HealthyQuotaBrush)
                            else mod.background(barColor)
                        }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatResetCountdown(bucket.resetAt, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已用 ${bucket.usedPercent}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = ClawColors.TextMuted
            )
        }
    }
}

private fun formatResetCountdown(resetAt: Long?, nowMs: Long): String {
    if (resetAt == null || resetAt <= 0L) return "重置时间：按滚动窗口恢复"
    val diff = resetAt - nowMs
    if (diff <= 0L) return "重置时间：即将刷新"
    val totalMinutes = diff / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val mins = totalMinutes % 60
    val resetClock = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(resetAt))
    return when {
        days > 0 -> "${days} 天 ${hours} 小时后重置 ($resetClock)"
        hours > 0 -> "${hours} 小时 ${mins} 分钟后重置 ($resetClock)"
        else -> "${mins.coerceAtLeast(1)} 分钟后重置 ($resetClock)"
    }
}

// ─── Sessions List Screen (Compact ChatGPT/WeChat List + Search + Time/Project View) ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionsScreen(
    connState: ConnectionUiState,
    sessions: List<SessionSummaryUi>,
    pinnedIds: Set<String>,
    onRefresh: () -> Unit,
    onTogglePin: (String) -> Unit,
    onOpenSession: (SessionSummaryUi) -> Unit,
    initialViewMode: SessionViewMode = SessionViewMode.TIME,
    initialSearchQuery: String = "",
    initialStatusFilter: SessionStatusFilter = SessionStatusFilter.ALL,
    workspaceConfig: WorkspaceConfigState = WorkspaceConfigState(),
    projectCreation: ProjectCreationState = ProjectCreationState(),
    defaultProjectsRootHint: String = "",
    antigravityModels: List<ModelOptionUi> = emptyList(),
    codexModels: List<ModelOptionUi> = emptyList(),
    onCreateProject: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onClearProjectError: () -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    initialShowCreateDialog: Boolean = false,
    onSwitchMode: (ConnectionMode) -> Unit = {},
    onPairSubmit: (String) -> Unit = {},
    onManualLanConnect: (String) -> Unit = {},
    onRetryDiscover: () -> Unit = {},
) {
    var searchQuery by rememberSaveable { mutableStateOf(initialSearchQuery) }
    var sourceFilter by rememberSaveable { mutableStateOf("all") }
    var statusFilterName by rememberSaveable { mutableStateOf(initialStatusFilter.name) }
    var viewModeName by rememberSaveable { mutableStateOf(initialViewMode.name) }
    var expandedProjects by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var actionTarget by remember { mutableStateOf<SessionDisplayModel?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(initialShowCreateDialog) }

    val effectiveProjectsRoot = workspaceConfig.projectsRootDir
        .ifBlank { defaultProjectsRootHint }
        .ifBlank { workspaceConfig.defaultDesktopDir }
        .ifBlank { "C:\\Users\\<用户>\\Desktop (C盘桌面)" }

    LaunchedEffect(projectCreation.lastCreatedSessionId, projectCreation.creating) {
        if (!projectCreation.creating && projectCreation.error == null && !projectCreation.lastCreatedSessionId.isNullOrBlank()) {
            showCreateDialog = false
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            desktopOnline = connState.paired && connState.desktopOnline,
            effectiveProjectsRoot = effectiveProjectsRoot,
            projectCreation = projectCreation,
            antigravityModels = antigravityModels,
            codexModels = codexModels,
            onRefreshModels = onRefreshModels,
            onOpenSettings = {
                showCreateDialog = false
                onOpenSettings()
            },
            onConfirmCreate = { name, src, modelId, modelLabel, prompt ->
                onCreateProject(name, src, modelId, modelLabel, prompt)
            },
            onDismiss = {
                onClearProjectError()
                showCreateDialog = false
            }
        )
    }

    val statusFilter = remember(statusFilterName) {
        runCatching { SessionStatusFilter.valueOf(statusFilterName) }.getOrDefault(SessionStatusFilter.ALL)
    }
    val viewMode = remember(viewModeName) {
        runCatching { SessionViewMode.valueOf(viewModeName) }.getOrDefault(SessionViewMode.TIME)
    }

    // Precompute humanized titles, non-duplicate previews, model tags & search corpus once per session list update
    val displayModels = remember(sessions) {
        sessions.map { buildSessionDisplayModel(it) }
    }

    val agyTotal = remember(sessions) { sessions.count { it.source == "antigravity" } }
    val codexTotal = remember(sessions) { sessions.count { it.source == "codex" } }

    // Status counts reflect currently selected source filter
    val sourceScoped = remember(displayModels, sourceFilter) {
        when (sourceFilter) {
            "antigravity" -> displayModels.filter { it.raw.source == "antigravity" }
            "codex" -> displayModels.filter { it.raw.source == "codex" }
            else -> displayModels
        }
    }
    val runningCount = remember(sourceScoped) { sourceScoped.count { it.raw.status == "running" } }
    val waitingCount = remember(sourceScoped) { sourceScoped.count { it.raw.status == "waiting_approval" } }
    val doneCount = remember(sourceScoped) {
        sourceScoped.count { it.raw.status != "running" && it.raw.status != "waiting_approval" }
    }

    val filtered = remember(displayModels, sourceFilter, statusFilter, searchQuery) {
        displayModels.filter { matchesSessionFilters(it, sourceFilter, statusFilter, searchQuery) }
    }

    val nowDayKey = remember(sessions) { System.currentTimeMillis() }
    val listEntries = remember(filtered, pinnedIds, viewMode, expandedProjects, searchQuery, nowDayKey) {
        buildSessionListEntries(
            filtered = filtered,
            pinnedIds = pinnedIds,
            viewMode = viewMode,
            expandedProjects = expandedProjects,
            searchActive = searchQuery.isNotBlank(),
            nowMs = nowDayKey
        )
    }

    val hasActiveFilters = searchQuery.isNotBlank() ||
        sourceFilter != "all" ||
        statusFilter != SessionStatusFilter.ALL

    // Long-press action dialog for Pin/Unpin & full metadata inspection
    actionTarget?.let { target ->
        val isPinned = target.raw.id in pinnedIds
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor = ClawColors.Surface,
            shape = ClawDimens.CardShape,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactSourceBadge(target.raw.source)
                    Text(
                        text = target.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsMetaRow(label = "所属分组", value = target.projectLabel)
                    if (target.modelTag.isNotBlank()) {
                        SettingsMetaRow(label = "当前模型", value = target.modelTag)
                    }
                    SettingsMetaRow(label = "会话编号", value = target.raw.id.take(12))
                    SettingsMetaRow(label = "当前状态", value = statusLabel(target.raw.status))
                }
            },
            confirmButton = {
                Button(
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Primary,
                        contentColor = ClawColors.OnPrimary
                    ),
                    onClick = {
                        onTogglePin(target.raw.id)
                        actionTarget = null
                    }
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isPinned) "取消置顶" else "置顶会话",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val s = target.raw
                        actionTarget = null
                        onOpenSession(s)
                    }
                ) {
                    Text("打开会话", color = ClawColors.TextSecondary)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val isLanMode = connState.connectionMode == ConnectionMode.LAN
        if (isLanMode && connState.socketStatus == SocketStatus.ERROR) {
            Box(modifier = Modifier.padding(horizontal = ClawDimens.ScreenPadding, vertical = 8.dp)) {
                LanMismatchAndDiscoveryCard(
                    connState = connState,
                    onRetryDiscover = onRetryDiscover,
                    onManualConnect = onManualLanConnect,
                    onSwitchToWan = { onSwitchMode(ConnectionMode.WAN) }
                )
            }
        } else if (isLanMode && !connState.paired) {
            Box(modifier = Modifier.padding(horizontal = ClawDimens.ScreenPadding, vertical = 8.dp)) {
                LanPairingGuideCard(
                    connState = connState,
                    onSubmitLanPairCode = onPairSubmit,
                    onManualConnect = onManualLanConnect
                )
            }
        } else if (!connState.desktopOnline) {
            Surface(
                color = ClawColors.WarningBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isLanMode && connState.socketStatus == SocketStatus.CONNECTING ->
                            connState.modeSwitchingHint ?: "正在扫描并连接局域网电脑..."
                        !connState.paired ->
                            "尚未配对桌面桥，请先在首页输入 6 位配对码。"
                        else ->
                            "桌面桥当前离线：以下为最近同步缓存列表，写操作已锁定。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.Warning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            HorizontalDivider(color = ClawColors.WarningBorder)
        }

        // Row 0: Youthful New-Project Quick Strip + Active Workspace Root Hint
        val shortRootDisplay = remember(effectiveProjectsRoot) {
            val clean = effectiveProjectsRoot.trimEnd('\\', '/')
            if (clean.contains("Desktop", ignoreCase = true) && clean.contains("Users", ignoreCase = true)) {
                "C盘桌面 (${clean.substringAfterLast('\\').substringAfterLast('/')})"
            } else {
                clean.substringAfterLast('\\').substringAfterLast('/').ifBlank { clean }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ClawDimens.ScreenPadding, end = ClawDimens.ScreenPadding, top = 8.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenSettings() }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = ClawColors.Accent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "工作区：$shortRootDisplay",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (connState.paired && connState.desktopOnline) ClawColors.PrimaryGradientBrush
                        else Brush.horizontalGradient(listOf(ClawColors.SurfaceElevated, ClawColors.SurfaceElevated))
                    )
                    .clickable {
                        onClearProjectError()
                        if (antigravityModels.isEmpty() || codexModels.isEmpty()) onRefreshModels()
                        showCreateDialog = true
                    }
                    .testTag("btn_new_project")
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CreateNewFolder,
                        contentDescription = null,
                        tint = if (connState.paired && connState.desktopOnline) ClawColors.OnPrimary else ClawColors.TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "新建项目",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (connState.paired && connState.desktopOnline) ClawColors.OnPrimary else ClawColors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        // Row 1: Instant Search Bar + Segmented View Switcher (时间 | 项目)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ClawDimens.ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = ClawColors.Surface,
                shape = RoundedCornerShape(10.dp),
                border = ClawDimens.HairlineBorder,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索",
                        tint = ClawColors.TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索标题、项目、模型或内容...",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = ClawColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                color = ClawColors.TextPrimary
                            ),
                            cursorBrush = SolidColor(ClawColors.Primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("session_search_input")
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" }
                                .testTag("session_search_clear"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空搜索",
                                tint = ClawColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Segmented control: 时间 | 项目
            Surface(
                color = ClawColors.Surface,
                shape = RoundedCornerShape(10.dp),
                border = ClawDimens.HairlineBorder,
                modifier = Modifier.height(38.dp)
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SegmentedViewTab(
                        label = "时间",
                        selected = viewMode == SessionViewMode.TIME,
                        tag = "view_mode_time",
                        onClick = { viewModeName = SessionViewMode.TIME.name }
                    )
                    SegmentedViewTab(
                        label = "项目",
                        selected = viewMode == SessionViewMode.PROJECT,
                        tag = "view_mode_project",
                        onClick = { viewModeName = SessionViewMode.PROJECT.name }
                    )
                }
            }
        }

        // Row 2: Source Filter Pills (全部 / Antigravity / Codex)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ClawDimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactFilterPill(
                label = "全部 (${sessions.size})",
                selected = sourceFilter == "all",
                tag = "filter_source_all",
                onClick = { sourceFilter = "all" }
            )
            CompactFilterPill(
                label = "Antigravity ($agyTotal)",
                selected = sourceFilter == "antigravity",
                tag = "filter_source_agy",
                onClick = { sourceFilter = "antigravity" }
            )
            CompactFilterPill(
                label = "Codex ($codexTotal)",
                selected = sourceFilter == "codex",
                tag = "filter_source_codex",
                onClick = { sourceFilter = "codex" }
            )
        }

        // Row 3: Status Filter Pills (全部 / 进行中 / 待确认 / 已完成)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ClawDimens.ScreenPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactFilterPill(
                label = "全部状态",
                selected = statusFilter == SessionStatusFilter.ALL,
                subtle = true,
                tag = "filter_status_all",
                onClick = { statusFilterName = SessionStatusFilter.ALL.name }
            )
            CompactFilterPill(
                label = if (runningCount > 0) "进行中 $runningCount" else "进行中",
                selected = statusFilter == SessionStatusFilter.RUNNING,
                dotColor = if (runningCount > 0) ClawColors.Success else null,
                subtle = true,
                tag = "filter_status_running",
                onClick = { statusFilterName = SessionStatusFilter.RUNNING.name }
            )
            CompactFilterPill(
                label = if (waitingCount > 0) "待确认 $waitingCount" else "待确认",
                selected = statusFilter == SessionStatusFilter.WAITING,
                dotColor = if (waitingCount > 0) ClawColors.Warning else null,
                subtle = true,
                tag = "filter_status_waiting",
                onClick = { statusFilterName = SessionStatusFilter.WAITING.name }
            )
            CompactFilterPill(
                label = "已完成 $doneCount",
                selected = statusFilter == SessionStatusFilter.DONE,
                subtle = true,
                tag = "filter_status_done",
                onClick = { statusFilterName = SessionStatusFilter.DONE.name }
            )
        }

        if (searchQuery.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawColors.Surface.copy(alpha = 0.6f))
                    .padding(horizontal = ClawDimens.ScreenPadding, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "匹配 ${filtered.size} / ${sessions.size} 个会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.TextSecondary
                )
                Text(
                    text = "重置筛选",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.Accent,
                    modifier = Modifier.clickable {
                        searchQuery = ""
                        sourceFilter = "all"
                        statusFilterName = SessionStatusFilter.ALL.name
                    }
                )
            }
        }

        HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)

        if (listEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = if (hasActiveFilters) "未找到匹配的会话" else "暂无会话数据",
                        style = MaterialTheme.typography.titleSmall,
                        color = ClawColors.TextPrimary
                    )
                    Text(
                        text = if (hasActiveFilters) "试试换个关键词（支持标题、项目、模型或预览内容），或清除当前筛选条件"
                        else "请确认桌面桥在线后点击刷新同步会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (hasActiveFilters) {
                        OutlinedButton(
                            onClick = {
                                searchQuery = ""
                                sourceFilter = "all"
                                statusFilterName = SessionStatusFilter.ALL.name
                            },
                            shape = ClawDimens.NestedShape,
                            border = ClawDimens.HairlineBorder
                        ) {
                            Text("清空搜索与筛选", color = ClawColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRefresh,
                            shape = ClawDimens.NestedShape,
                            border = ClawDimens.HairlineBorder
                        ) {
                            Text("刷新会话列表", color = ClawColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 2.dp, bottom = 20.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("sessions_lazy_list")
        ) {
            items(
                items = listEntries,
                key = { it.key },
                contentType = { it.contentType }
            ) { entry ->
                when (entry) {
                    is SessionListEntry.SectionHeader -> {
                        CompactSectionHeader(
                            title = entry.title,
                            count = entry.count,
                            kind = entry.kind
                        )
                    }

                    is SessionListEntry.CollapsibleProjectHeader -> {
                        CollapsibleProjectHeaderRow(
                            projectName = entry.projectName,
                            count = entry.count,
                            hasActive = entry.hasActive,
                            expanded = entry.expanded,
                            onToggle = {
                                expandedProjects = expandedProjects + (entry.projectName to !entry.expanded)
                            }
                        )
                    }

                    is SessionListEntry.SessionRow -> {
                        CompactSessionRow(
                            model = entry.model,
                            isPinned = entry.isPinned,
                            showProjectInline = entry.showProjectInline,
                            showBottomDivider = entry.showBottomDivider,
                            nowMs = nowDayKey,
                            onClick = { onOpenSession(entry.model.raw) },
                            onLongClick = { actionTarget = entry.model }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedViewTab(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) ClawColors.Primary else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) ClawColors.OnPrimary else ClawColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CompactFilterPill(
    label: String,
    selected: Boolean,
    dotColor: Color? = null,
    subtle: Boolean = false,
    tag: String = "",
    onClick: () -> Unit,
) {
    val bg = when {
        selected && !subtle -> ClawColors.Primary
        selected && subtle -> ClawColors.SurfaceElevated
        else -> Color.Transparent
    }
    val fg = when {
        selected && !subtle -> ClawColors.OnPrimary
        selected && subtle -> ClawColors.TextPrimary
        else -> ClawColors.TextSecondary
    }
    val borderColor = when {
        selected && !subtle -> ClawColors.Primary
        selected && subtle -> ClawColors.BorderStrong
        else -> ClawColors.Border
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = if (subtle) 4.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (subtle) 11.5.sp else 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactSectionHeader(
    title: String,
    count: Int,
    kind: HeaderKind,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClawColors.Canvas)
            .padding(horizontal = ClawDimens.ScreenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (kind) {
            HeaderKind.PINNED -> Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = ClawColors.Accent,
                modifier = Modifier.size(12.dp)
            )
            HeaderKind.RUNNING -> PulsingStatusDot(color = ClawColors.Success, sizeDp = 7)
            HeaderKind.TIME -> Unit
        }
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (kind == HeaderKind.RUNNING) ClawColors.Success else ClawColors.TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun CollapsibleProjectHeaderRow(
    projectName: String,
    count: Int,
    hasActive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ClawColors.Surface.copy(alpha = 0.55f))
                .clickable(onClick = onToggle)
                .padding(horizontal = ClawDimens.ScreenPadding, vertical = 7.dp)
                .testTag("project_group_$projectName"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = if (hasActive) ClawColors.Accent else ClawColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = projectName,
                style = MaterialTheme.typography.labelMedium,
                color = ClawColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = ClawColors.SurfaceElevated,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    fontFamily = FontFamily.Monospace,
                    color = ClawColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = if (expanded) "收起分组" else "展开分组",
                tint = ClawColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactSessionRow(
    model: SessionDisplayModel,
    isPinned: Boolean,
    showProjectInline: Boolean,
    showBottomDivider: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val item = model.raw
    val isRunning = item.status == "running"
    val isWaiting = item.status == "waiting_approval"
    val isError = item.status == "error"

    val rowBg = when {
        isRunning -> ClawColors.RunningRowBg
        isWaiting -> ClawColors.WarningBg
        else -> Color.Transparent
    }

    val accentStripeColor = when {
        isRunning -> ClawColors.Success
        isWaiting -> ClawColors.Warning
        isError -> ClawColors.Error
        else -> Color.Transparent
    }

    val showProjChip = showProjectInline &&
        isMeaningfulProject(model.projectLabel) &&
        !model.displayTitle.contains(model.projectLabel, ignoreCase = true)

    val previewLineText = remember(model, showProjChip) {
        buildString {
            if (model.previewText.isNotBlank()) {
                append(model.previewText)
            }
            if (showProjChip && !model.previewText.contains(model.projectLabel, ignoreCase = true)) {
                if (isNotEmpty()) append(" · ")
                append(model.projectLabel)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("session_row_${item.source}_${item.id}")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (accentStripeColor != Color.Transparent) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(42.dp)
                        .align(Alignment.CenterStart)
                        .background(accentStripeColor)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ClawDimens.ScreenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Compact AGY / Codex badge
                CompactSourceBadge(item.source)

                // Center: Line 1 (Title) + Line 2 (Model chip + Preview)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = "已置顶",
                                tint = ClawColors.Accent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = model.displayTitle,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 14.5.sp,
                                fontWeight = if (isRunning || isWaiting) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = ClawColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (model.modelTag.isNotBlank()) {
                            Surface(
                                color = ClawColors.SurfaceElevated,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, ClawColors.BorderStrong)
                            ) {
                                Text(
                                    text = model.modelTag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = ClawColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 112.dp)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (previewLineText.isNotBlank()) {
                            Text(
                                text = previewLineText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = ClawColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }

                // Right: Top time, Bottom status indicator
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = formatCompactSessionTime(item.updatedAt, nowMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        color = ClawColors.TextMuted,
                        maxLines = 1
                    )
                    when {
                        isRunning -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PulsingStatusDot(color = ClawColors.Success, sizeDp = 7)
                            Text(
                                text = "进行中",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = ClawColors.Success,
                                maxLines = 1
                            )
                        }
                        isWaiting -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ClawColors.Warning)
                            )
                            Text(
                                text = "待确认",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = ClawColors.Warning,
                                maxLines = 1
                            )
                        }
                        isError -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ClawColors.Error)
                            )
                            Text(
                                text = "异常",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = ClawColors.Error,
                                maxLines = 1
                            )
                        }
                        else -> Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ClawColors.TextMuted.copy(alpha = 0.45f))
                        )
                    }
                }
            }
        }

        if (showBottomDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp, end = ClawDimens.ScreenPadding),
                thickness = 0.5.dp,
                color = ClawColors.Border.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun CompactSourceBadge(source: String) {
    val isCodex = source == "codex"
    val bg = if (isCodex) ClawColors.BadgeCodexBg else ClawColors.BadgeAgyBg
    val border = if (isCodex) ClawColors.BadgeCodexBorder else ClawColors.BadgeAgyBorder
    val fg = if (isCodex) ClawColors.BadgeCodexText else ClawColors.BadgeAgyText
    Surface(
        color = bg,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.width(42.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = if (isCodex) "Codex" else "AGY",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PulsingStatusDot(color: Color, sizeDp: Int = 7) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

// ─── New Project Dialog ──────────────────────────────────────────────────────

private val INVALID_FOLDER_CHAR_REGEX = Regex("""[<>:"/\\|?*\x00-\x1F]""")

@Composable
internal fun CreateProjectDialog(
    desktopOnline: Boolean,
    effectiveProjectsRoot: String,
    projectCreation: ProjectCreationState,
    antigravityModels: List<ModelOptionUi>,
    codexModels: List<ModelOptionUi>,
    onRefreshModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmCreate: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!projectCreation.creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CreateProjectDialogCard(
            desktopOnline = desktopOnline,
            effectiveProjectsRoot = effectiveProjectsRoot,
            projectCreation = projectCreation,
            antigravityModels = antigravityModels,
            codexModels = codexModels,
            onOpenSettings = onOpenSettings,
            onConfirmCreate = onConfirmCreate,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun CreateProjectDialogCard(
    desktopOnline: Boolean,
    effectiveProjectsRoot: String,
    projectCreation: ProjectCreationState,
    antigravityModels: List<ModelOptionUi>,
    codexModels: List<ModelOptionUi>,
    onOpenSettings: () -> Unit,
    onConfirmCreate: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var folderName by rememberSaveable { mutableStateOf("") }
    var selectedSource by rememberSaveable { mutableStateOf("antigravity") }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var selectedModelLabel by rememberSaveable { mutableStateOf("") }
    var initialPrompt by rememberSaveable { mutableStateOf("") }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    val activeModelList = if (selectedSource == "codex") codexModels else antigravityModels
    val cleanFolder = folderName.trim().trimEnd('.', ' ')
    val previewFullPath = remember(effectiveProjectsRoot, cleanFolder) {
        val base = effectiveProjectsRoot.trimEnd('\\', '/')
        val sep = if (base.contains('/')) "/" else "\\"
        if (cleanFolder.isEmpty()) "$base$sep<新文件夹名>" else "$base$sep$cleanFolder"
    }

    if (true) {
        Surface(
            shape = ClawDimens.CardShape,
            color = ClawColors.Surface,
            border = BorderStroke(1.dp, ClawColors.PrimaryBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .testTag("create_project_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = null,
                            tint = ClawColors.Accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "在电脑新建 Project",
                            style = MaterialTheme.typography.titleMedium,
                            color = ClawColors.TextPrimary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !projectCreation.creating,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary)
                    }
                }

                // Engine Switcher: Antigravity vs Codex
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "antigravity" to "Antigravity 项目",
                        "codex" to "Codex 项目"
                    ).forEach { (srcKey, srcTitle) ->
                        val active = selectedSource == srcKey
                        Surface(
                            color = if (active) ClawColors.Primary else ClawColors.SurfaceElevated,
                            shape = ClawDimens.NestedShape,
                            border = BorderStroke(1.dp, if (active) ClawColors.Primary else ClawColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .clip(ClawDimens.NestedShape)
                                .clickable(enabled = !projectCreation.creating) {
                                    selectedSource = srcKey
                                    selectedModelId = ""
                                    selectedModelLabel = ""
                                }
                                .testTag("new_proj_source_$srcKey")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp)
                            ) {
                                Text(
                                    text = srcTitle,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (active) ClawColors.OnPrimary else ClawColors.TextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = folderName,
                    onValueChange = {
                        folderName = it.take(80)
                        localValidationError = null
                    },
                    enabled = !projectCreation.creating,
                    label = { Text("自定义项目文件夹名") },
                    placeholder = { Text("例如：校园跑腿小程序 / ai-agent-demo") },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_proj_name_input")
                )

                // Live Workspace Directory Preview + Quick Jump to Settings
                Surface(
                    color = ClawColors.Canvas,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "电脑创建路径（默认 C 盘桌面）",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.TextMuted
                            )
                            Text(
                                text = "去设置更改目录",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.Accent,
                                modifier = Modifier.clickable(enabled = !projectCreation.creating) { onOpenSettings() }
                            )
                        }
                        Text(
                            text = previewFullPath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                            color = ClawColors.TextPrimary
                        )
                    }
                }

                // Optional Model Selection Chips
                if (activeModelList.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "初始任务模型（可选）",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClawColors.TextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactFilterPill(
                                label = "默认模型",
                                selected = selectedModelId.isEmpty(),
                                onClick = {
                                    selectedModelId = ""
                                    selectedModelLabel = ""
                                }
                            )
                            activeModelList.take(10).forEach { m ->
                                CompactFilterPill(
                                    label = m.label,
                                    selected = selectedModelId == m.modelId,
                                    onClick = {
                                        selectedModelId = m.modelId
                                        selectedModelLabel = m.label
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = initialPrompt,
                    onValueChange = { initialPrompt = it },
                    enabled = !projectCreation.creating,
                    label = { Text("首条开发指令（可选，留空仅创建空项目）") },
                    placeholder = { Text("例如：初始化一个现代化的 Vue3 + Tailwind 项目结构...") },
                    maxLines = 3,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_proj_prompt_input")
                )

                val displayError = localValidationError ?: projectCreation.error
                if (!displayError.isNullOrBlank()) {
                    Surface(
                        color = ClawColors.ErrorBg,
                        shape = ClawDimens.NestedShape,
                        border = BorderStroke(1.dp, ClawColors.ErrorBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayError,
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.Error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (!desktopOnline) {
                    Text(
                        text = "提示：桌面桥当前离线，请先恢复连接后再新建项目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.Warning
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !projectCreation.creating,
                        shape = ClawDimens.NestedShape,
                        border = ClawDimens.HairlineBorder,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消", color = ClawColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            when {
                                cleanFolder.isEmpty() -> {
                                    localValidationError = "请输入项目文件夹名称"
                                }
                                cleanFolder == "." || cleanFolder == ".." -> {
                                    localValidationError = "文件夹名称不合法"
                                }
                                INVALID_FOLDER_CHAR_REGEX.containsMatchIn(cleanFolder) -> {
                                    localValidationError = "文件夹名不能包含 \\ / : * ? \" < > | 等特殊符号"
                                }
                                else -> {
                                    localValidationError = null
                                    onConfirmCreate(
                                        cleanFolder,
                                        selectedSource,
                                        selectedModelId,
                                        selectedModelLabel,
                                        initialPrompt.trim()
                                    )
                                }
                            }
                        },
                        enabled = desktopOnline && !projectCreation.creating,
                        shape = ClawDimens.NestedShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClawColors.Primary,
                            contentColor = ClawColors.OnPrimary
                        ),
                        modifier = Modifier
                            .weight(1.6f)
                            .testTag("new_proj_confirm_btn")
                    ) {
                        if (projectCreation.creating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = ClawColors.OnPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在电脑创建...", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("创建并进入对话", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ─── Settings Screen ─────────────────────────────────────────────────────────

@Composable
internal fun SettingsScreenContent(
    appSettings: AppSettings,
    connState: ConnectionUiState,
    isWhitelisted: Boolean,
    workspaceConfig: WorkspaceConfigState = WorkspaceConfigState(),
    lanSnapshot: LanPairingSnapshot = LanPairingSnapshot(),
    onConnectManualLanHost: (String) -> Unit = {},
    onUnpairLan: () -> Unit = {},
    onSaveProjectsRootDir: (String) -> Unit = {},
    onResetProjectsRootDir: () -> Unit = {},
    onClearWorkspaceFeedback: () -> Unit = {},
    onSetDarkMode: (Boolean) -> Unit = {},
    onUpdateSectionColor: (ThemeSectionKey, Int?) -> Unit = { _, _ -> },
    onApplyThemePreset: (SectionColorOverrides) -> Unit = {},
    onResetThemeColors: () -> Unit = {},
    onSaveRelayConfig: (String, Boolean) -> Unit,
    onUnpairAndReset: () -> Unit,
    onSavePin: (String) -> Unit,
    onUpdatePrefs: (Boolean?, Boolean?, Boolean?, String?, String?) -> Unit,
    onUpdateTaskDoneNotify: (Boolean) -> Unit = {},
    onRequestBatteryWhitelist: () -> Unit,
    onOpenRomAutostartSettings: () -> Unit,
    onNavigateHomeForPair: () -> Unit,
) {
    var relayUrlInput by remember(appSettings.relayUrl) { mutableStateOf(appSettings.relayUrl) }
    var trustSelfSigned by remember(appSettings.trustSelfSigned) { mutableStateOf(appSettings.trustSelfSigned) }
    var showSelfSignedWarning by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val syncedRootDir = workspaceConfig.projectsRootDir
        .ifBlank { appSettings.projectsRootDir }
        .ifBlank { workspaceConfig.defaultDesktopDir }
    var projectsRootInput by remember(syncedRootDir) { mutableStateOf(syncedRootDir) }

    var pinInput by remember { mutableStateOf("") }
    var dndStartInput by remember(appSettings.dndStart) { mutableStateOf(appSettings.dndStart) }
    var dndEndInput by remember(appSettings.dndEnd) { mutableStateOf(appSettings.dndEnd) }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(3_000L)
            statusBanner = null
        }
    }

    LaunchedEffect(workspaceConfig.feedbackMessage, workspaceConfig.seq) {
        if (workspaceConfig.feedbackMessage != null) {
            delay(3_000L)
            onClearWorkspaceFeedback()
        }
    }

    if (showSelfSignedWarning) {
        AlertDialog(
            onDismissRequest = { showSelfSignedWarning = false },
            containerColor = ClawColors.Surface,
            shape = ClawDimens.CardShape,
            icon = {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = ClawColors.Error)
            },
            title = { Text("安全警告：信任自签证书", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "开启后将跳过标准 CA 证书链校验，存在中间人攻击风险。仅在测试环境明确使用自签 TLS 证书时开启，生产中继请保持关闭。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Error,
                        contentColor = ClawColors.OnPrimary
                    ),
                    onClick = {
                        trustSelfSigned = true
                        showSelfSignedWarning = false
                    }
                ) {
                    Text("知晓风险并开启", style = MaterialTheme.typography.labelMedium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelfSignedWarning = false }) {
                    Text("取消", color = ClawColors.TextSecondary)
                }
            }
        )
    }

    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = ClawColors.OnPrimary,
        checkedTrackColor = ClawColors.Accent,
        uncheckedThumbColor = ClawColors.TextSecondary,
        uncheckedTrackColor = ClawColors.SurfaceElevated,
        uncheckedBorderColor = ClawColors.BorderStrong,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ClawDimens.ScreenPadding, vertical = ClawDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(ClawDimens.SectionGap)
    ) {
        if (statusBanner != null) {
            Surface(
                color = ClawColors.AccentSoft,
                shape = ClawDimens.NestedShape,
                border = BorderStroke(1.dp, ClawColors.AccentBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusBanner ?: "",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextPrimary
                )
            }
        }

        if (workspaceConfig.feedbackMessage != null) {
            Surface(
                color = if (workspaceConfig.isError) ClawColors.ErrorBg else ClawColors.AccentSoft,
                shape = ClawDimens.NestedShape,
                border = BorderStroke(1.dp, if (workspaceConfig.isError) ClawColors.ErrorBorder else ClawColors.AccentBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = workspaceConfig.feedbackMessage,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (workspaceConfig.isError) ClawColors.Error else ClawColors.TextPrimary
                )
            }
        }

        // 0A. 外观模式（黑夜 / 白天一键切换）与六大板块自由取色器
        ThemeCustomizationCard(
            themeConfig = appSettings.themeConfig,
            onSetDarkMode = onSetDarkMode,
            onUpdateSectionColor = onUpdateSectionColor,
            onApplyPreset = onApplyThemePreset,
            onResetCurrentMode = onResetThemeColors,
        )

        // 0. 新建项目默认工作目录（默认 C 盘桌面，可随时更改与恢复）
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = BorderStroke(1.dp, ClawColors.PrimaryBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = ClawColors.Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "新建项目工作区目录",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary
                    )
                }
                Text(
                    text = "手机新建 Projects 时，将在电脑此路径下创建自定义命名的项目文件夹。默认位于 C 盘桌面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextSecondary
                )
                OutlinedTextField(
                    value = projectsRootInput,
                    onValueChange = { projectsRootInput = it },
                    label = { Text("电脑项目根目录路径") },
                    placeholder = {
                        Text(
                            workspaceConfig.defaultDesktopDir.ifBlank { "C:\\Users\\<User>\\Desktop" }
                        )
                    },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_projects_root_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val def = workspaceConfig.defaultDesktopDir
                            if (def.isNotBlank()) projectsRootInput = def
                            onResetProjectsRootDir()
                        },
                        enabled = !workspaceConfig.saving,
                        shape = ClawDimens.NestedShape,
                        border = ClawDimens.HairlineBorder,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_reset_projects_root_btn")
                    ) {
                        Text(
                            text = "恢复 C 盘桌面",
                            style = MaterialTheme.typography.labelMedium,
                            color = ClawColors.TextPrimary
                        )
                    }
                    Button(
                        onClick = {
                            onSaveProjectsRootDir(projectsRootInput.trim())
                        },
                        enabled = projectsRootInput.isNotBlank() && !workspaceConfig.saving,
                        shape = ClawDimens.NestedShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClawColors.Primary,
                            contentColor = ClawColors.OnPrimary
                        ),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("settings_save_projects_root_btn")
                    ) {
                        Text(
                            text = if (workspaceConfig.saving) "同步中..." else "保存默认目录",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 1A. 内网直连 (LAN) 独立配置与 Keystore 凭证管理
        LanSettingsCard(
            lanSnapshot = lanSnapshot,
            connState = connState,
            onConnectManualHost = { host ->
                onConnectManualLanHost(host)
                statusBanner = "已切换至内网直连并尝试连接 $host"
            },
            onUnpairLan = {
                onUnpairLan()
                statusBanner = "已解除内网直连绑定（不影响外网配对）"
            }
        )

        // 1B. 外网中继服务器设置
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = ClawDimens.HairlineBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
                Text("中继服务器", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                OutlinedTextField(
                    value = relayUrlInput,
                    onValueChange = { relayUrlInput = it },
                    label = { Text("中继 WSS 地址（可自由更换）") },
                    placeholder = { Text("wss://relay.example.com") },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = ClawColors.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("信任自签 TLS 证书", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextPrimary)
                        Text(
                            text = "默认严格校验正规证书，开启需承担中间人风险",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextMuted
                        )
                    }
                    Switch(
                        checked = trustSelfSigned,
                        colors = switchColors,
                        onCheckedChange = { wantEnable ->
                            if (wantEnable) showSelfSignedWarning = true
                            else trustSelfSigned = false
                        }
                    )
                }
                Button(
                    onClick = {
                        val normalized = SettingsStore.normalizeRelayUrl(relayUrlInput)
                        val changed = normalized != appSettings.relayUrl
                        onSaveRelayConfig(normalized, trustSelfSigned)
                        statusBanner = if (changed) {
                            "已保存新中继并断开旧连接，请返回首页输入新配对码"
                        } else {
                            "连接配置已保存并重连"
                        }
                        if (changed) onNavigateHomeForPair()
                    },
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Primary,
                        contentColor = ClawColors.OnPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存中继设置并重连", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 2. 配对状态与设备信息
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = ClawDimens.HairlineBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
                Text("外网配对与设备信息", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsMetaRow(label = "本机设备 ID", value = appSettings.deviceId)
                    SettingsMetaRow(
                        label = "外网绑定状态",
                        value = if (appSettings.isPaired) "已绑定 · ${appSettings.pairedBridgeId}" else "未绑定"
                    )
                }
                OutlinedButton(
                    onClick = {
                        onUnpairAndReset()
                        statusBanner = "已解除外网中继配对，请在首页外网模式下重新输入 6 位配对码"
                        onNavigateHomeForPair()
                    },
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重新配对外网中继 / 解除外网绑定", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                }
            }
        }

        // 3. 后台保活三件套
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = ClawDimens.HairlineBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = ClawColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("后台保活配置", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isWhitelisted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (isWhitelisted) ClawColors.Success else ClawColors.Warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isWhitelisted) "电池优化白名单：已开启（无限制）"
                        else "电池优化白名单：未加入（锁屏后可能被系统冻结）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isWhitelisted) ClawColors.Success else ClawColors.Warning
                    )
                }
                // Stacked clean full-width actions guarantee zero label truncation on 360dp small screens
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onRequestBatteryWhitelist,
                        shape = ClawDimens.NestedShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = ClawColors.SurfaceElevated,
                            contentColor = ClawColors.TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. 开启忽略电池优化（省电策略选无限制）", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onOpenRomAutostartSettings,
                        shape = ClawDimens.NestedShape,
                        border = ClawDimens.HairlineBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. 前往开启后台自启动与锁屏通知权限", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                    }
                }
                Text(
                    text = "HyperOS / MIUI 保活核对：省电策略选「无限制」，开启「后台自启动」，并在通知管理中开启「锁屏通知」与「悬浮通知」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextMuted
                )
            }
        }

        // 4. 安全 PIN 与通知设置
        Card(
            shape = ClawDimens.CardShape,
            colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
            border = ClawDimens.HairlineBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(ClawDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = ClawColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("安全 PIN 与通知", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                }
                Text(
                    text = if (appSettings.hasPin) "安全 PIN：已设置（高风险命令审批强制要求校验）"
                    else "安全 PIN：未设置（建议设置 4–6 位数字 PIN）",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("新 PIN (4-6位数字)") },
                        singleLine = true,
                        shape = ClawDimens.NestedShape,
                        colors = clawTextFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = pinInput.length in 4..6,
                        shape = ClawDimens.NestedShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClawColors.Primary,
                            contentColor = ClawColors.OnPrimary
                        ),
                        onClick = {
                            onSavePin(pinInput)
                            pinInput = ""
                            statusBanner = "安全 PIN 已加密保存（SHA-256）"
                        }
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider(color = ClawColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("发送消息也需要 PIN 校验", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextPrimary)
                        Text(
                            text = "高风险命令审批始终强制需要 PIN（不可关闭）",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextMuted
                        )
                    }
                    Switch(
                        checked = appSettings.requirePinForSend,
                        colors = switchColors,
                        onCheckedChange = { req -> onUpdatePrefs(req, null, null, null, null) }
                    )
                }

                HorizontalDivider(color = ClawColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用推送通知", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextPrimary)
                    Switch(
                        checked = appSettings.notificationsEnabled,
                        colors = switchColors,
                        onCheckedChange = { enabled -> onUpdatePrefs(null, enabled, null, null, null) }
                    )
                }

                HorizontalDivider(color = ClawColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "会话任务结束通知",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClawColors.TextPrimary
                        )
                        Text(
                            text = "每个会话任务执行完成时发送消息到手机通知栏，点击直达该对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextMuted
                        )
                    }
                    Switch(
                        checked = appSettings.notificationsEnabled && appSettings.taskDoneNotifyEnabled,
                        enabled = appSettings.notificationsEnabled,
                        colors = switchColors,
                        onCheckedChange = { enabled -> onUpdateTaskDoneNotify(enabled) },
                        modifier = Modifier.testTag("switch_task_done_notify")
                    )
                }

                HorizontalDivider(color = ClawColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开启免打扰静音时段", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextPrimary)
                    Switch(
                        checked = appSettings.dndEnabled,
                        colors = switchColors,
                        onCheckedChange = { enabled -> onUpdatePrefs(null, null, enabled, null, null) }
                    )
                }

                if (appSettings.dndEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dndStartInput,
                            onValueChange = { dndStartInput = it },
                            label = { Text("免打扰开始") },
                            singleLine = true,
                            shape = ClawDimens.NestedShape,
                            colors = clawTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = dndEndInput,
                            onValueChange = { dndEndInput = it },
                            label = { Text("免打扰结束") },
                            singleLine = true,
                            shape = ClawDimens.NestedShape,
                            colors = clawTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdatePrefs(null, null, null, dndStartInput.trim(), dndEndInput.trim())
                            statusBanner = "免打扰时段已保存"
                        },
                        shape = ClawDimens.NestedShape,
                        border = ClawDimens.HairlineBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存免打扰时段", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                    }
                }
            }
        }

        Spacer(Modifier.height(ClawDimens.SpaceSm))
    }
}

@Composable
internal fun SettingsMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = ClawColors.TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = ClawColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun clawTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ClawColors.BorderStrong,
    unfocusedBorderColor = ClawColors.Border,
    focusedContainerColor = ClawColors.SurfaceElevated,
    unfocusedContainerColor = ClawColors.SurfaceElevated,
    focusedTextColor = ClawColors.TextPrimary,
    unfocusedTextColor = ClawColors.TextPrimary,
    focusedLabelColor = ClawColors.TextSecondary,
    unfocusedLabelColor = ClawColors.TextMuted,
    cursorColor = ClawColors.Primary,
)
