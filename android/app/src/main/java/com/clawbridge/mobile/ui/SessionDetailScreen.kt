package com.clawbridge.mobile.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clawbridge.mobile.data.ActionFeedbackState
import com.clawbridge.mobile.data.AppSettings
import com.clawbridge.mobile.data.ArtifactItemUi
import com.clawbridge.mobile.data.ArtifactPreviewState
import com.clawbridge.mobile.data.ChatMessageUi
import com.clawbridge.mobile.data.DownloadStatusState
import com.clawbridge.mobile.data.ModelOptionUi
import com.clawbridge.mobile.data.PendingApprovalUi
import com.clawbridge.mobile.data.SessionDetailState
import com.clawbridge.mobile.data.SessionMetricsState
import com.clawbridge.mobile.data.SettingsStore
import com.clawbridge.mobile.data.StagedAttachmentUi
import com.clawbridge.mobile.data.SubagentUi
import com.clawbridge.mobile.data.UploadStatusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SessionDetailScreen(
    detail: SessionDetailState,
    metrics: SessionMetricsState? = null,
    initialMetricsExpanded: Boolean = false,
    appSettings: AppSettings,
    settingsStore: SettingsStore?,
    desktopOnline: Boolean,
    sessionApprovals: List<PendingApprovalUi>,
    actionFeedback: ActionFeedbackState,
    previewState: ArtifactPreviewState?,
    downloadStatus: DownloadStatusState,
    uploadStatus: UploadStatusState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectModel: (ModelOptionUi) -> Unit,
    onRefreshModels: () -> Unit,
    onSendChat: (String) -> Unit,
    onSendQuickCommand: (String, String) -> Unit,
    onInspectUri: (Uri) -> StagedAttachmentUi?,
    onUploadAttachment: (StagedAttachmentUi, Boolean, String) -> Unit,
    onClearUploadBanner: () -> Unit,
    onRespondApproval: (PendingApprovalUi, String, Boolean) -> Unit,
    onClearActionFeedback: () -> Unit,
    onPreviewArtifact: (ArtifactItemUi) -> Unit,
    onDismissPreview: () -> Unit,
    onDownloadArtifact: (String, String) -> Unit,
    onDirectFileDownload: (String, String) -> Unit,
    onClearDownloadBanner: () -> Unit,
    onOpenDownloadedUri: (String, String?) -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var draftMessage by rememberSaveable(detail.session.id) { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var stagedAttachment by remember(detail.session.id) { mutableStateOf<StagedAttachmentUi?>(null) }
    var freeCopyDialogText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val meta = onInspectUri(uri)
            if (meta != null) stagedAttachment = meta.copy(isImage = true)
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val meta = onInspectUri(uri)
            if (meta != null) stagedAttachment = meta
        }
    }

    fun copyTextWithToast(textToCopy: String, label: String = "已复制到剪贴板") {
        if (textToCopy.isBlank()) return
        clipboard.setText(AnnotatedString(textToCopy))
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    }

    var pendingPinGate by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    // ─── Last-Seen Reading Position Restoration & Persistence ────────────────
    var initialScrollRestored by remember(detail.session.id) { mutableStateOf(false) }
    val currentMessages by rememberUpdatedState(detail.messages)

    LaunchedEffect(detail.session.id, detail.messages.isNotEmpty()) {
        if (!initialScrollRestored && detail.messages.isNotEmpty()) {
            runCatching {
                val saved = settingsStore?.getSessionScrollPosition(detail.session.id)
                if (saved != null) {
                    val anchorIdx = detail.messages.indexOfFirst { it.id == saved.anchorMessageId }
                    if (anchorIdx >= 0) {
                        listState.scrollToItem(anchorIdx, saved.offset)
                    } else {
                        val boundedIdx = saved.index.coerceIn(0, detail.messages.lastIndex)
                        listState.scrollToItem(boundedIdx, saved.offset)
                    }
                } else {
                    listState.scrollToItem(detail.messages.lastIndex)
                }
            }
            initialScrollRestored = true
        }
    }

    LaunchedEffect(detail.session.id, initialScrollRestored) {
        if (!initialScrollRestored || settingsStore == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(180L)
            .collect { (idx, offset) ->
                val msgs = currentMessages
                if (msgs.isNotEmpty() && idx in msgs.indices) {
                    settingsStore.saveSessionScrollPosition(
                        sessionId = detail.session.id,
                        anchorMessageId = msgs[idx].id,
                        index = idx,
                        offset = offset
                    )
                }
            }
    }

    DisposableEffect(detail.session.id) {
        onDispose {
            if (initialScrollRestored && settingsStore != null && currentMessages.isNotEmpty()) {
                val idx = listState.firstVisibleItemIndex.coerceIn(0, currentMessages.lastIndex)
                val offset = listState.firstVisibleItemScrollOffset
                settingsStore.saveSessionScrollPosition(
                    sessionId = detail.session.id,
                    anchorMessageId = currentMessages[idx].id,
                    index = idx,
                    offset = offset
                )
            }
        }
    }

    val lastMsgKey = remember(detail.messages) {
        val last = detail.messages.lastOrNull()
        "${detail.messages.size}:${last?.id}:${last?.text?.length ?: 0}:${last?.status}"
    }
    LaunchedEffect(lastMsgKey) {
        if (initialScrollRestored && subTab == 0 && detail.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = !listState.canScrollForward || (detail.messages.lastIndex - lastVisible <= 2)
            if (nearBottom) {
                runCatching {
                    listState.scrollToItem(detail.messages.lastIndex)
                }
            }
        }
    }

    val showJumpToBottom by remember {
        derivedStateOf {
            subTab == 0 && detail.messages.size > 3 && listState.canScrollForward
        }
    }

    // ─── Modals ──────────────────────────────────────────────────────────────
    val pinGate = pendingPinGate
    if (pinGate != null && settingsStore != null) {
        PinVerifyOrSetupDialog(
            hasPinConfigured = appSettings.hasPin,
            promptTitle = pinGate.first,
            promptSubtitle = pinGate.second,
            settingsStore = settingsStore,
            onVerified = {
                val cb = pinGate.third
                pendingPinGate = null
                cb()
            },
            onDismiss = { pendingPinGate = null }
        )
    }

    if (previewState != null) {
        ArtifactPreviewDialog(
            preview = previewState,
            onDismiss = onDismissPreview,
            onDownload = {
                onDownloadArtifact(previewState.artifactId, previewState.name)
            }
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            source = detail.session.source,
            currentModelId = detail.session.currentModelId,
            currentModelLabel = detail.session.currentModelLabel,
            availableModels = detail.availableModels,
            desktopOnline = desktopOnline,
            onRefreshModels = onRefreshModels,
            onSelect = { chosen ->
                showModelPicker = false
                onSelectModel(chosen)
            },
            onDismiss = { showModelPicker = false }
        )
    }

    val activeCopyModalText = freeCopyDialogText
    if (activeCopyModalText != null) {
        FreeTextCopyDialog(
            text = activeCopyModalText,
            onCopyAll = {
                copyTextWithToast(activeCopyModalText, "已复制整条消息")
                freeCopyDialogText = null
            },
            onDismiss = { freeCopyDialogText = null }
        )
    }

    fun gateWriteWithOptionalPin(title: String, subtitle: String, forcePin: Boolean, action: () -> Unit) {
        if ((forcePin || appSettings.requirePinForSend) && settingsStore != null) {
            pendingPinGate = Triple(title, subtitle, action)
        } else {
            action()
        }
    }

    Scaffold(
        containerColor = ClawColors.Canvas,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ClawColors.Canvas,
                        titleContentColor = ClawColors.TextPrimary,
                        navigationIconContentColor = ClawColors.TextPrimary,
                        actionIconContentColor = ClawColors.TextSecondary,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回会话列表")
                        }
                    },
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(detail.session.source)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = detail.session.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ClawColors.TextPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SessionStatusDot(detail.session.status)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = buildString {
                                        append(statusLabel(detail.session.status))
                                        append(" · ")
                                        append(detail.session.project)
                                        if (detail.syncing && detail.messages.isNotEmpty()) {
                                            append(" · 同步中")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClawColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            if (detail.syncing && detail.messages.isNotEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 1.8.dp,
                                    color = ClawColors.TextSecondary
                                )
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新会话")
                            }
                        }
                    }
                )
                HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)
            }
        },
        bottomBar = {
            ChatComposerBottomBar(
                desktopOnline = desktopOnline,
                sending = actionFeedback.sending || uploadStatus.active,
                draft = draftMessage,
                stagedAttachment = stagedAttachment,
                onDraftChange = { draftMessage = it },
                onPickImage = { pickImageLauncher.launch("image/*") },
                onPickFile = { pickFileLauncher.launch("*/*") },
                onRemoveAttachment = { stagedAttachment = null },
                onUploadFileOnly = {
                    val att = stagedAttachment ?: return@ChatComposerBottomBar
                    gateWriteWithOptionalPin(
                        title = "确认上传文件到电脑",
                        subtitle = "即将上传「${att.fileName}」到电脑当前项目目录",
                        forcePin = false
                    ) {
                        stagedAttachment = null
                        onUploadAttachment(att, false, "")
                    }
                },
                onQuickCommand = { key, label ->
                    gateWriteWithOptionalPin(
                        title = "确认发送快捷指令",
                        subtitle = "即将发送快捷指令：$label",
                        forcePin = false
                    ) {
                        onSendQuickCommand(key, label)
                        scope.launch {
                            runCatching {
                                if (detail.messages.isNotEmpty()) {
                                    listState.scrollToItem(detail.messages.lastIndex)
                                }
                            }
                        }
                    }
                },
                onSend = {
                    val att = stagedAttachment
                    val toSend = draftMessage.trim()
                    if (att != null) {
                        gateWriteWithOptionalPin(
                            title = if (att.isImage) "确认发送图片到电脑会话" else "确认上传文件并发送消息",
                            subtitle = "即将发送「${att.fileName}」到桌面会话",
                            forcePin = false
                        ) {
                            stagedAttachment = null
                            draftMessage = ""
                            onUploadAttachment(att, true, toSend)
                            scope.launch {
                                runCatching {
                                    if (detail.messages.isNotEmpty()) {
                                        listState.scrollToItem(detail.messages.lastIndex)
                                    }
                                }
                            }
                        }
                    } else if (toSend.isNotEmpty()) {
                        gateWriteWithOptionalPin(
                            title = "确认发送消息",
                            subtitle = "请输入安全 PIN 后发送消息",
                            forcePin = false
                        ) {
                            draftMessage = ""
                            onSendChat(toSend)
                            scope.launch {
                                runCatching {
                                    if (detail.messages.isNotEmpty()) {
                                        listState.scrollToItem(detail.messages.lastIndex)
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (actionFeedback.banner != null) {
                Surface(
                    color = if (actionFeedback.isError) ClawColors.ErrorBg else ClawColors.AccentSoft,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = actionFeedback.banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (actionFeedback.isError) ClawColors.Error else ClawColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearActionFeedback, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "关闭提示",
                                tint = ClawColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = if (actionFeedback.isError) ClawColors.ErrorBorder else ClawColors.AccentBorder)
            }

            if (uploadStatus.active || uploadStatus.message != null || uploadStatus.error != null) {
                UploadBanner(status = uploadStatus, onDismiss = onClearUploadBanner)
            }

            if (downloadStatus.active || downloadStatus.savedMessage != null || downloadStatus.error != null) {
                DownloadBanner(
                    status = downloadStatus,
                    onOpen = {
                        val u = downloadStatus.savedUriString
                        if (u != null) onOpenDownloadedUri(u, downloadStatus.mimeType)
                    },
                    onDismiss = onClearDownloadBanner
                )
            }

            if (sessionApprovals.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = ClawDimens.ScreenPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sessionApprovals.forEach { appr ->
                        PendingApprovalCard(
                            item = appr,
                            desktopOnline = desktopOnline,
                            onApproveClick = {
                                if (appr.riskLevel == "high") {
                                    pendingPinGate = Triple(
                                        "高风险操作审批确认",
                                        "即将批准高风险命令：\n${appr.commandPreview.take(120)}",
                                    ) {
                                        onRespondApproval(appr, "approve", true)
                                    }
                                } else {
                                    onRespondApproval(appr, "approve", false)
                                }
                            },
                            onRejectClick = {
                                if (appSettings.hasPin) {
                                    pendingPinGate = Triple(
                                        "确认拒绝该操作",
                                        "请输入安全 PIN 确认拒绝执行",
                                    ) {
                                        onRespondApproval(appr, "reject", true)
                                    }
                                } else {
                                    onRespondApproval(appr, "reject", false)
                                }
                            }
                        )
                    }
                }
            }

            // Compact ChatGPT-style Model Switcher Strip
            Surface(
                color = ClawColors.Surface,
                shape = ClawDimens.NestedShape,
                border = ClawDimens.HairlineBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ClawDimens.ScreenPadding, vertical = 8.dp)
                    .clip(ClawDimens.NestedShape)
                    .clickable(enabled = desktopOnline) {
                        if (detail.availableModels.isEmpty()) onRefreshModels()
                        showModelPicker = true
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = ClawColors.Accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = detail.session.currentModelLabel.ifBlank { "默认模型" },
                            style = MaterialTheme.typography.labelMedium,
                            color = ClawColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (desktopOnline) "切换模型" else "桌面离线",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (desktopOnline) ClawColors.TextSecondary else ClawColors.TextMuted
                        )
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = "切换任务模型",
                            tint = ClawColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Horizontally scrollable sub-tabs so 360dp small screens never wrap awkwardly
            val subChipColors = FilterChipDefaults.filterChipColors(
                containerColor = ClawColors.Surface,
                labelColor = ClawColors.TextSecondary,
                selectedContainerColor = ClawColors.Primary,
                selectedLabelColor = ClawColors.OnPrimary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ClawDimens.ScreenPadding, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(ClawDimens.SpaceSm)
            ) {
                FilterChip(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    colors = subChipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = subTab == 0,
                        borderColor = ClawColors.Border,
                        selectedBorderColor = ClawColors.Primary
                    ),
                    label = { Text("消息流 (${detail.messages.size})", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    colors = subChipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = subTab == 1,
                        borderColor = ClawColors.Border,
                        selectedBorderColor = ClawColors.Primary
                    ),
                    label = { Text("子代理 (${detail.subagents.size})", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = subTab == 2,
                    onClick = { subTab = 2 },
                    colors = subChipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = subTab == 2,
                        borderColor = ClawColors.Border,
                        selectedBorderColor = ClawColors.Primary
                    ),
                    label = { Text("产物与 Diff (${detail.artifacts.size})", style = MaterialTheme.typography.labelMedium) }
                )
            }

            if (detail.loading && detail.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ClawColors.Primary, strokeWidth = 2.dp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (subTab) {
                        0 -> MessageStreamList(
                            messages = detail.messages,
                            listState = listState,
                            desktopOnline = desktopOnline,
                            onCopyText = { txt, label -> copyTextWithToast(txt, label) },
                            onOpenFreeSelect = { txt -> freeCopyDialogText = txt },
                            onDirectFileDownload = onDirectFileDownload
                        )
                        1 -> SubagentsList(detail.subagents)
                        2 -> ArtifactsList(
                            artifacts = detail.artifacts,
                            onPreview = onPreviewArtifact,
                            onDownload = { item -> onDownloadArtifact(item.artifactId, item.name) }
                        )
                    }

                    if (showJumpToBottom) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        listState.animateScrollToItem(detail.messages.lastIndex)
                                    }
                                }
                            },
                            shape = CircleShape,
                            containerColor = ClawColors.SurfaceElevated,
                            contentColor = ClawColors.TextPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("最新消息", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Real-time Speed, Token Usage & Cost Estimation Bar (hidden cleanly if unavailable/disabled)
            val validMetrics = metrics?.takeIf {
                it.sessionId.isBlank() || it.sessionId == detail.session.id
            }
            if (validMetrics != null && validMetrics.hasDisplayableMetrics()) {
                var metricsExpanded by rememberSaveable(detail.session.id) {
                    mutableStateOf(initialMetricsExpanded)
                }
                SessionMetricsBar(
                    metrics = validMetrics,
                    expanded = metricsExpanded,
                    onToggleExpand = { metricsExpanded = !metricsExpanded }
                )
            }
        }
    }
}

@Composable
internal fun SessionMetricsBar(
    metrics: SessionMetricsState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val isGenerating = metrics.state == "generating"
    val borderColor = if (isGenerating) ClawColors.AccentBorder else ClawColors.Border

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_metrics_bar")
            .background(ClawColors.Surface)
    ) {
        HorizontalDivider(color = borderColor, thickness = 1.dp)

        // Primary compact header row (always visible, tap to expand/collapse)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("session_metrics_toggle")
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Live status dot + Hero Decode TPS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) ClawColors.Accent else ClawColors.TextMuted)
                )
                Spacer(Modifier.width(6.dp))
                val heroSpeedText = when {
                    metrics.decodeTps != null -> {
                        val prefix = if (metrics.decodeTpsEstimated) "≈" else ""
                        "$prefix${formatTpsValue(metrics.decodeTps)} t/s"
                    }
                    isGenerating -> "生成中..."
                    metrics.e2eTps != null -> {
                        val prefix = if (metrics.e2eTpsEstimated) "≈" else ""
                        "E2E $prefix${formatTpsValue(metrics.e2eTps)} t/s"
                    }
                    else -> "统计"
                }
                Text(
                    text = heroSpeedText,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                    ),
                    color = if (isGenerating) ClawColors.Accent else ClawColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Right: Current reply output tokens + current reply estimated cost + chevron
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (metrics.outputTokens > 0L || isGenerating) {
                    val tokPrefix = if (metrics.outputTokensEstimated) "≈" else ""
                    Text(
                        text = "$tokPrefix${formatCompactTokens(metrics.outputTokens)} tok",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                        color = ClawColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                val replyCost = metrics.estCostReply
                if (replyCost != null && (replyCost > 0.0 || metrics.outputTokens > 0L)) {
                    val costPrefix = if (metrics.costEstimated) "≈" else ""
                    Text(
                        text = "预估 $costPrefix${formatUsdShort(replyCost)}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                        color = ClawColors.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = if (expanded) "收起指标详情" else "展开指标详情",
                    tint = ClawColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Expandable secondary metrics drawer
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("session_metrics_drawer")
                    .background(ClawColors.Canvas)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: TTFT | E2E TPS | Cache Hit % (only when present)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ttftText = metrics.ttftMs?.let { ms ->
                        val prefix = if (metrics.ttftEstimated) "≈" else ""
                        "$prefix${formatLatencyMs(ms)}"
                    } ?: "--"
                    MetricsMiniCell(
                        label = "首字延迟 TTFT",
                        value = ttftText,
                        modifier = Modifier.weight(1f)
                    )

                    val e2eText = metrics.e2eTps?.let { tps ->
                        val prefix = if (metrics.e2eTpsEstimated) "≈" else ""
                        "$prefix${formatTpsValue(tps)} t/s"
                    } ?: "--"
                    MetricsMiniCell(
                        label = "端到端 TPS",
                        value = e2eText,
                        modifier = Modifier.weight(1f)
                    )

                    if (metrics.cacheHitPct != null) {
                        MetricsMiniCell(
                            label = "缓存命中",
                            value = String.format(Locale.US, "%.1f%%", metrics.cacheHitPct),
                            valueColor = ClawColors.Accent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Row 2: Context usage progress bar (only when contextPct is present)
                val ctxPct = metrics.contextPct
                if (ctxPct != null) {
                    val clampedPct = ctxPct.coerceIn(0.0, 100.0)
                    val barColor = when {
                        clampedPct >= 85.0 -> ClawColors.Error
                        clampedPct >= 60.0 -> ClawColors.Warning
                        else -> ClawColors.Accent
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "上下文用量",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.TextSecondary,
                            )
                            val ctxDetail = if (metrics.contextUsed != null && metrics.contextMax != null && metrics.contextMax > 0L) {
                                "${formatCompactTokens(metrics.contextUsed)} / ${formatCompactTokens(metrics.contextMax)} · ${String.format(Locale.US, "%.1f%%", clampedPct)}"
                            } else {
                                String.format(Locale.US, "%.1f%%", clampedPct)
                            }
                            Text(
                                text = ctxDetail,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = ClawColors.TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (clampedPct / 100.0).toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = barColor,
                            trackColor = ClawColors.SurfaceElevated,
                        )
                    }
                }

                // Row 3: Session cumulative estimated cost + public API pricing disclaimer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sessCostText = metrics.estCostSession?.let { formatUsdShort(it) } ?: "--"
                    Text(
                        text = "本会话累计预估 $sessCostText",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = ClawColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (metrics.priceDefaulted) "按公开 API 单价估算(默认)" else "按公开 API 单价估算",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = ClawColors.TextMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsMiniCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = ClawColors.TextPrimary,
) {
    Surface(
        color = ClawColors.Surface,
        shape = RoundedCornerShape(8.dp),
        border = ClawDimens.HairlineBorder,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ClawColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                ),
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatTpsValue(tps: Double): String {
    return if (tps >= 100.0) String.format(Locale.US, "%.0f", tps)
    else String.format(Locale.US, "%.1f", tps)
}

internal fun formatCompactTokens(tokens: Long): String {
    val v = tokens.coerceAtLeast(0L)
    return when {
        v >= 1_000_000L -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000L -> String.format(Locale.US, "%.1fk", v / 1_000.0)
        else -> v.toString()
    }
}

internal fun formatLatencyMs(ms: Long): String {
    val v = ms.coerceAtLeast(0L)
    return when {
        v >= 10_000L -> String.format(Locale.US, "%.1fs", v / 1000.0)
        v >= 1_000L -> String.format(Locale.US, "%.2fs", v / 1000.0)
        else -> "${v}ms"
    }
}

internal fun formatUsdShort(usd: Double): String {
    val v = usd.coerceAtLeast(0.0)
    return when {
        v >= 1.0 -> String.format(Locale.US, "$%.2f", v)
        v >= 0.01 -> String.format(Locale.US, "$%.3f", v)
        v > 0.0 -> String.format(Locale.US, "$%.4f", v)
        else -> "$0.00"
    }
}

@Composable
private fun ChatComposerBottomBar(
    desktopOnline: Boolean,
    sending: Boolean,
    draft: String,
    stagedAttachment: StagedAttachmentUi?,
    onDraftChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onUploadFileOnly: () -> Unit,
    onQuickCommand: (String, String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClawColors.Surface)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!desktopOnline) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = ClawColors.Warning,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "桌面桥当前离线，回复、传文件与快捷指令已禁用",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.Warning
                    )
                }
            }

            if (stagedAttachment != null) {
                Surface(
                    color = ClawColors.SurfaceElevated,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (stagedAttachment.isImage) Icons.Rounded.Image else Icons.Rounded.AttachFile,
                                contentDescription = null,
                                tint = ClawColors.Accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stagedAttachment.fileName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ClawColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append(if (stagedAttachment.isImage) "图片直传会话" else "文件实时上传")
                                        if (stagedAttachment.sizeBytes > 0) {
                                            append(" · ${formatFileSize(stagedAttachment.sizeBytes)}")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClawColors.TextMuted
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onUploadFileOnly,
                                enabled = desktopOnline && !sending,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("仅存电脑", style = MaterialTheme.typography.labelSmall, color = ClawColors.Accent)
                            }
                            IconButton(
                                onClick = onRemoveAttachment,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "移除附件",
                                    tint = ClawColors.TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Unified vector-icon chips for Image/File upload & Quick Commands (zero emojis)
            val assistChipColors = AssistChipDefaults.assistChipColors(
                containerColor = ClawColors.SurfaceElevated,
                labelColor = ClawColors.TextPrimary,
                leadingIconContentColor = ClawColors.TextSecondary,
            )
            val assistChipBorder = AssistChipDefaults.assistChipBorder(
                enabled = desktopOnline && !sending,
                borderColor = ClawColors.Border
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onPickImage,
                    enabled = desktopOnline && !sending,
                    colors = assistChipColors,
                    border = assistChipBorder,
                    leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("发图片", style = MaterialTheme.typography.labelMedium) }
                )
                AssistChip(
                    onClick = onPickFile,
                    enabled = desktopOnline && !sending,
                    colors = assistChipColors,
                    border = assistChipBorder,
                    leadingIcon = { Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("传文件", style = MaterialTheme.typography.labelMedium) }
                )
                AssistChip(
                    onClick = { onQuickCommand("continue", "继续") },
                    enabled = desktopOnline && !sending,
                    colors = assistChipColors,
                    border = assistChipBorder,
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("继续", style = MaterialTheme.typography.labelMedium) }
                )
                AssistChip(
                    onClick = { onQuickCommand("start_plan", "按计划开工") },
                    enabled = desktopOnline && !sending,
                    colors = assistChipColors,
                    border = assistChipBorder,
                    leadingIcon = { Icon(Icons.Rounded.RocketLaunch, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text("按计划开工", style = MaterialTheme.typography.labelMedium) }
                )
                AssistChip(
                    onClick = { onQuickCommand("report_progress", "先汇报进度") },
                    enabled = desktopOnline && !sending,
                    colors = assistChipColors,
                    border = assistChipBorder,
                    leadingIcon = { Icon(Icons.Rounded.Summarize, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text("先汇报进度", style = MaterialTheme.typography.labelMedium) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = desktopOnline && !sending,
                    shape = ClawDimens.PillShape,
                    colors = clawTextFieldColors(),
                    placeholder = {
                        Text(
                            text = when {
                                !desktopOnline -> "桌面离线，禁止发送"
                                stagedAttachment != null -> "可补充说明后发送（或点上方仅存电脑）..."
                                else -> "发送消息给当前会话..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClawColors.TextMuted
                        )
                    },
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onSend,
                    enabled = desktopOnline && !sending && (draft.isNotBlank() || stagedAttachment != null),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Primary,
                        contentColor = ClawColors.OnPrimary,
                        disabledContainerColor = ClawColors.SurfaceElevated,
                        disabledContentColor = ClawColors.TextMuted,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ClawColors.OnPrimary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "发送",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadBanner(
    status: UploadStatusState,
    onDismiss: () -> Unit,
) {
    Surface(
        color = if (status.error != null) ClawColors.ErrorBg else ClawColors.AccentSoft,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        status.active -> "正在上传 ${status.fileName} (${(status.progress * 100).toInt()}%)..."
                        status.error != null -> status.error
                        else -> status.message ?: "上传完成"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null) ClawColors.Error else ClawColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (!status.active) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
            }
            if (status.active) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    color = ClawColors.Accent,
                    trackColor = ClawColors.SurfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                )
            }
        }
    }
    HorizontalDivider(color = if (status.error != null) ClawColors.ErrorBorder else ClawColors.AccentBorder)
}

@Composable
private fun DownloadBanner(
    status: DownloadStatusState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val isApk = status.fileName.endsWith(".apk", ignoreCase = true)
    Surface(
        color = if (status.error != null) ClawColors.ErrorBg else ClawColors.SurfaceElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        status.active -> "正在下载 ${status.fileName} (${(status.progress * 100).toInt()}%)..."
                        status.error != null -> status.error
                        else -> status.savedMessage ?: "下载完成"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null) ClawColors.Error else ClawColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!status.active && status.savedUriString != null) {
                        Button(
                            onClick = onOpen,
                            shape = ClawDimens.BadgeShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClawColors.Primary,
                                contentColor = ClawColors.OnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(if (isApk) "安装 / 打开" else "打开文件", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (!status.active) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (status.active) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    color = ClawColors.Accent,
                    trackColor = ClawColors.Surface,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                )
            }
        }
    }
    HorizontalDivider(color = if (status.error != null) ClawColors.ErrorBorder else ClawColors.Border)
}

private data class DetectedDesktopFileLink(
    val label: String,
    val rawPath: String,
    val priority: Int,
)

private val MARKDOWN_FILE_LINK_REGEX = Regex("""\[([^\]]+)\]\((file:///[^)\s]+)\)""", RegexOption.IGNORE_CASE)
private val STANDALONE_WIN_FILE_REGEX = Regex(
    """(?<![A-Za-z0-9_/\\])([A-Za-z]:[/\\][^\s"'<>|`*?\r\n()]+?\.(?:apk|zip|7z|rar|pdf|png|jpg|jpeg|webp|gif|mp4|mp3|md|txt|json|csv|docx|xlsx|pptx|html|log|exe|msi))(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE
)

private fun classifyFileLinkPriority(pathStr: String): Int {
    val ext = pathStr.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk", "exe", "msi", "zip", "7z", "rar", "pdf",
        "png", "jpg", "jpeg", "webp", "gif", "mp4", "mp3",
        "docx", "xlsx", "pptx", "csv" -> 0
        "md", "txt", "html", "log" -> 1
        else -> 2
    }
}

private fun extractDesktopFileLinks(rawText: String): List<DetectedDesktopFileLink> {
    if (rawText.isBlank()) return emptyList()
    val found = LinkedHashMap<String, DetectedDesktopFileLink>()

    for (m in MARKDOWN_FILE_LINK_REGEX.findAll(rawText)) {
        val label = m.groupValues[1].trim()
        val url = m.groupValues[2].trim().replace(Regex("#L\\d+(?:-L?\\d+)?$", RegexOption.IGNORE_CASE), "")
        val cleanPath = runCatching { java.net.URLDecoder.decode(url.removePrefix("file:///"), "UTF-8") }
            .getOrDefault(url.removePrefix("file:///"))
        val fileName = cleanPath.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.contains('.')) {
            val key = cleanPath.lowercase().replace('\\', '/')
            found.putIfAbsent(
                key,
                DetectedDesktopFileLink(
                    label = label.ifBlank { fileName },
                    rawPath = cleanPath,
                    priority = classifyFileLinkPriority(cleanPath)
                )
            )
        }
    }

    val strippedMd = MARKDOWN_FILE_LINK_REGEX.replace(rawText, " ")
    for (m in STANDALONE_WIN_FILE_REGEX.findAll(strippedMd)) {
        val winPath = m.groupValues[1].trim()
        val fileName = winPath.substringAfterLast('/').substringAfterLast('\\')
        val key = winPath.lowercase().replace('\\', '/')
        found.putIfAbsent(
            key,
            DetectedDesktopFileLink(
                label = fileName,
                rawPath = winPath,
                priority = classifyFileLinkPriority(winPath)
            )
        )
    }

    return found.values.sortedBy { it.priority }
}

private fun cleanDisplayProse(rawText: String): String {
    return MARKDOWN_FILE_LINK_REGEX.replace(rawText) { m ->
        val label = m.groupValues[1].trim()
        val url = m.groupValues[2].trim().removePrefix("file:///")
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        if (label.equals(decoded, ignoreCase = true) || decoded.endsWith("/$label", ignoreCase = true)) {
            "$label ($decoded)"
        } else {
            "$label ($decoded)"
        }
    }
}

@Composable
private fun MessageStreamList(
    messages: List<ChatMessageUi>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    desktopOnline: Boolean,
    onCopyText: (String, String) -> Unit,
    onOpenFreeSelect: (String) -> Unit,
    onDirectFileDownload: (String, String) -> Unit,
) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "该会话暂无消息记录",
                style = MaterialTheme.typography.bodyMedium,
                color = ClawColors.TextMuted
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = ClawDimens.ScreenPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(messages, key = { idx, msg -> "msg-$idx-${msg.id}" }) { _, msg ->
            if (msg.role == "user") {
                UserMessageBubble(
                    msg = msg,
                    desktopOnline = desktopOnline,
                    onCopyText = onCopyText,
                    onOpenFreeSelect = onOpenFreeSelect,
                    onDirectFileDownload = onDirectFileDownload
                )
            } else {
                AgentMessageCard(
                    msg = msg,
                    desktopOnline = desktopOnline,
                    onCopyText = onCopyText,
                    onOpenFreeSelect = onOpenFreeSelect,
                    onDirectFileDownload = onDirectFileDownload
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageBubble(
    msg: ChatMessageUi,
    desktopOnline: Boolean,
    onCopyText: (String, String) -> Unit,
    onOpenFreeSelect: (String) -> Unit,
    onDirectFileDownload: (String, String) -> Unit,
) {
    val displayClean = remember(msg.text) { cleanDisplayProse(msg.text) }
    val detectedFiles = remember(msg.text) { extractDesktopFileLinks(msg.text) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        // ChatGPT-style calm charcoal rounded user bubble
        Surface(
            color = ClawColors.SurfaceElevated,
            contentColor = ClawColors.TextPrimary,
            shape = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp),
            border = ClawDimens.HairlineBorder,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onOpenFreeSelect(displayClean) }
                )
        ) {
            Text(
                text = displayClean,
                style = MaterialTheme.typography.bodyLarge,
                color = ClawColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        if (detectedFiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            detectedFiles.forEach { link ->
                InlineFileDownloadCard(
                    link = link,
                    desktopOnline = desktopOnline,
                    onDownload = { onDirectFileDownload(link.rawPath, link.label) }
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp, end = 2.dp)
        ) {
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextSecondary,
                modifier = Modifier.clickable { onCopyText(displayClean, "已复制消息文字") }
            )
            Text(
                text = "选词",
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextSecondary,
                modifier = Modifier.clickable { onOpenFreeSelect(displayClean) }
            )
            Text(
                text = formatShortTime(msg.ts),
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextMuted
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentMessageCard(
    msg: ChatMessageUi,
    desktopOnline: Boolean,
    onCopyText: (String, String) -> Unit,
    onOpenFreeSelect: (String) -> Unit,
    onDirectFileDownload: (String, String) -> Unit,
) {
    var expandedTools by rememberSaveable(msg.id) { mutableStateOf(false) }
    val displayClean = remember(msg.text) { cleanDisplayProse(msg.text) }
    val detectedFiles = remember(msg.text) { extractDesktopFileLinks(msg.text) }

    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = ClawDimens.HairlineBorder,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { if (displayClean.isNotBlank()) onOpenFreeSelect(displayClean) }
            )
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (msg.toolSteps.isNotEmpty() || msg.thinkingSummary.isNotBlank()) {
                Surface(
                    color = ClawColors.SurfaceElevated,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ClawDimens.NestedShape)
                        .clickable { expandedTools = !expandedTools }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (msg.toolSteps.isNotEmpty()) "已执行 ${msg.toolSteps.size} 个步骤" else "思考过程摘要",
                                style = MaterialTheme.typography.labelMedium,
                                color = ClawColors.TextSecondary
                            )
                            Icon(
                                imageVector = if (expandedTools) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = ClawColors.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (expandedTools) {
                            Spacer(Modifier.height(6.dp))
                            if (msg.thinkingSummary.isNotBlank()) {
                                Text(
                                    text = msg.thinkingSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClawColors.TextMuted
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            msg.toolSteps.forEach { step ->
                                Text(
                                    text = "· $step",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ClawColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            if (msg.text.isNotBlank()) {
                FormattedMarkdownBlocks(
                    rawText = displayClean,
                    onCopyCode = { code -> onCopyText(code, "已复制代码片段") }
                )
            }

            if (detectedFiles.isNotEmpty()) {
                val primaryFiles = remember(detectedFiles) { detectedFiles.filter { it.priority <= 1 } }
                val codeFiles = remember(detectedFiles) { detectedFiles.filter { it.priority > 1 } }
                var expandCodeLinks by rememberSaveable(msg.id) { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    primaryFiles.forEach { link ->
                        InlineFileDownloadCard(
                            link = link,
                            desktopOnline = desktopOnline,
                            onDownload = { onDirectFileDownload(link.rawPath, link.label) }
                        )
                    }
                    if (codeFiles.isNotEmpty()) {
                        Surface(
                            color = ClawColors.SurfaceElevated,
                            shape = ClawDimens.NestedShape,
                            border = ClawDimens.HairlineBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ClawDimens.NestedShape)
                                .clickable { expandCodeLinks = !expandCodeLinks }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "引用的源码文件 (${codeFiles.size}) · 点击${if (expandCodeLinks) "收起" else "展开"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClawColors.TextSecondary
                                )
                                Icon(
                                    imageVector = if (expandCodeLinks) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = ClawColors.TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (expandCodeLinks) {
                            codeFiles.forEach { link ->
                                InlineFileDownloadCard(
                                    link = link,
                                    desktopOnline = desktopOnline,
                                    onDownload = { onDirectFileDownload(link.rawPath, link.label) }
                                )
                            }
                        }
                    }
                }
            }

            if (msg.status == "streaming") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = ClawColors.Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "实时输出同步中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.Accent
                    )
                }
            } else if (msg.status == "error") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = ClawColors.Error, modifier = Modifier.size(14.dp))
                    Text(
                        text = "该步骤执行报错",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.Error
                    )
                }
            }

            HorizontalDivider(color = ClawColors.Border.copy(alpha = 0.6f), thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatShortTime(msg.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.TextMuted
                )
                if (displayClean.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "自由选词",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClawColors.TextSecondary,
                            modifier = Modifier
                                .clip(ClawDimens.BadgeShape)
                                .clickable { onOpenFreeSelect(displayClean) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(ClawDimens.BadgeShape)
                                .clickable { onCopyText(displayClean, "已复制整条回复") }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "复制全文",
                                tint = ClawColors.TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "复制全文",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineFileDownloadCard(
    link: DetectedDesktopFileLink,
    desktopOnline: Boolean,
    onDownload: () -> Unit,
) {
    val isApk = link.rawPath.endsWith(".apk", ignoreCase = true)
    Surface(
        color = ClawColors.SurfaceElevated,
        shape = ClawDimens.NestedShape,
        border = ClawDimens.HairlineBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isApk) Icons.Rounded.Inventory2 else Icons.Rounded.Description,
                contentDescription = null,
                tint = ClawColors.Accent,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = link.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ClawColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = link.rawPath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = ClawColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = onDownload,
                enabled = desktopOnline,
                shape = ClawDimens.BadgeShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = ClawColors.Primary,
                    contentColor = ClawColors.OnPrimary,
                    disabledContainerColor = ClawColors.Surface,
                    disabledContentColor = ClawColors.TextMuted
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isApk) "下载安装包" else "下载", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FreeTextCopyDialog(
    text: String,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = ClawDimens.CardShape,
            color = ClawColors.Surface,
            border = ClawDimens.HairlineBorder,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(500.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("选择复制聊天文字", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                        Text(
                            text = "长按下方文字即可自由拖选复制，或点击一键复制全部",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary)
                    }
                }
                HorizontalDivider(color = ClawColors.Border, modifier = Modifier.padding(vertical = 10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ClawColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = ClawColors.TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onCopyAll,
                        shape = ClawDimens.NestedShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClawColors.Primary,
                            contentColor = ClawColors.OnPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("一键复制全部", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedMarkdownBlocks(
    rawText: String,
    onCopyCode: (String) -> Unit,
) {
    val segments = remember(rawText) { splitCodeBlocks(rawText) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((isCode, content) in segments) {
            if (isCode) {
                Surface(
                    color = ClawColors.SurfaceCode,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ClawColors.SurfaceCodeHeader)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "代码片段",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.TextMuted
                            )
                            Text(
                                text = "复制代码",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClawColors.Accent,
                                modifier = Modifier.clickable { onCopyCode(content) }
                            )
                        }
                        Text(
                            text = content,
                            color = ClawColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ClawColors.TextPrimary
                )
            }
        }
    }
}

private fun splitCodeBlocks(text: String): List<Pair<Boolean, String>> {
    val result = mutableListOf<Pair<Boolean, String>>()
    val regex = Regex("```[a-zA-Z0-9_-]*\\n?([\\s\\S]*?)```")
    var lastIdx = 0
    for (m in regex.findAll(text)) {
        if (m.range.first > lastIdx) {
            val prose = text.substring(lastIdx, m.range.first).trim()
            if (prose.isNotEmpty()) result.add(false to prose)
        }
        val code = (m.groups[1]?.value ?: "").trimEnd()
        if (code.isNotEmpty()) result.add(true to code)
        lastIdx = m.range.last + 1
    }
    if (lastIdx < text.length) {
        val tail = text.substring(lastIdx).trim()
        if (tail.isNotEmpty()) result.add(false to tail)
    }
    return result
}

@Composable
private fun SubagentsList(subagents: List<SubagentUi>) {
    if (subagents.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("当前会话未调用子代理", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextMuted)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(ClawDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ClawDimens.ItemGap),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(subagents, key = { idx, sub -> "sub-$idx-${sub.id}" }) { _, sub ->
            Card(
                shape = ClawDimens.CardShape,
                colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
                border = ClawDimens.HairlineBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(ClawDimens.CardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = ClawColors.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = sub.role,
                                style = MaterialTheme.typography.titleSmall,
                                color = ClawColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SessionStatusDot(sub.status)
                            Spacer(Modifier.width(5.dp))
                            Text(statusLabel(sub.status), style = MaterialTheme.typography.labelSmall, color = ClawColors.TextSecondary)
                        }
                    }
                    Text(
                        text = "类型: ${sub.agentName} · 共 ${sub.stepCount} 步 · ${formatShortTime(sub.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextMuted
                    )
                    if (sub.preview.isNotBlank()) {
                        Text(
                            text = sub.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactsList(
    artifacts: List<ArtifactItemUi>,
    onPreview: (ArtifactItemUi) -> Unit,
    onDownload: (ArtifactItemUi) -> Unit,
) {
    if (artifacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("当前会话暂无产物或文件变更", style = MaterialTheme.typography.bodyMedium, color = ClawColors.TextMuted)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(ClawDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ClawDimens.ItemGap),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(artifacts, key = { idx, item -> "art-$idx-${item.artifactId}" }) { _, item ->
            Card(
                shape = ClawDimens.CardShape,
                colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
                border = ClawDimens.HairlineBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(ClawDimens.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (item.kind) {
                            "image" -> Icons.Rounded.Image
                            "diff" -> Icons.Rounded.Code
                            else -> if (item.name.endsWith(".apk", ignoreCase = true)) Icons.Rounded.Inventory2 else Icons.Rounded.Description
                        }
                        Icon(icon, contentDescription = null, tint = ClawColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = ClawColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.kind == "diff") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("代码变更摘要", style = MaterialTheme.typography.labelSmall, color = ClawColors.TextMuted)
                                    Text("+${item.addLines}", style = MaterialTheme.typography.labelSmall, color = ClawColors.Success)
                                    Text("-${item.delLines}", style = MaterialTheme.typography.labelSmall, color = ClawColors.Error)
                                }
                            } else {
                                Text(
                                    text = "${formatFileSize(item.sizeBytes)} · ${formatShortTime(item.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClawColors.TextMuted
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onPreview(item) },
                            shape = ClawDimens.NestedShape,
                            border = ClawDimens.HairlineBorder,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = ClawColors.TextSecondary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (item.kind == "diff") "查看 Diff" else "预览", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { onDownload(item) },
                            shape = ClawDimens.NestedShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = ClawColors.Primary,
                                contentColor = ClawColors.OnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("下载到手机", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactPreviewDialog(
    preview: ArtifactPreviewState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ClawColors.Canvas,
            shape = ClawDimens.CardShape,
            border = ClawDimens.HairlineBorder,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preview.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (preview.canDownload && !preview.loading) {
                            Button(
                                onClick = onDownload,
                                shape = ClawDimens.NestedShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ClawColors.Primary,
                                    contentColor = ClawColors.OnPrimary
                                )
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存文件", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = ClawColors.TextSecondary)
                        }
                    }
                }
                HorizontalDivider(color = ClawColors.Border)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        preview.loading -> CircularProgressIndicator(color = ClawColors.Primary)
                        preview.error != null -> Text(preview.error, color = ClawColors.Error)
                        preview.kind == "image" && preview.imageBytes != null -> ZoomableImageView(preview.imageBytes)
                        preview.kind == "diff" && preview.textContent != null -> DiffCodeView(preview.textContent)
                        preview.textContent != null -> {
                            SelectionContainer {
                                Text(
                                    text = preview.textContent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = ClawColors.TextPrimary,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                        else -> Text("该文件类型暂不支持直接渲染，请点击右上角保存到手机打开。", color = ClawColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImageView(bytes: ByteArray) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, bytes) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / sample > 2560) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + offsetChange else Offset.Zero
    }

    val bmp = bitmap
    if (bmp == null) {
        CircularProgressIndicator(color = ClawColors.Primary)
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "产物图片预览",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .transformable(state = state)
        )
    }
}

@Composable
private fun DiffCodeView(diffText: String) {
    val annotated = remember(diffText) {
        buildAnnotatedString {
            val lines = diffText.split("\n")
            lines.forEachIndexed { i, line ->
                val lineColor = when {
                    line.startsWith("+++") || line.startsWith("---") -> ClawColors.TextMuted
                    line.startsWith("@@") -> ClawColors.Info
                    line.startsWith("+") -> ClawColors.Success
                    line.startsWith("-") -> ClawColors.Error
                    else -> ClawColors.TextPrimary
                }
                withStyle(SpanStyle(color = lineColor)) {
                    append(line)
                }
                if (i < lines.lastIndex) append("\n")
            }
        }
    }
    Surface(
        color = ClawColors.SurfaceCode,
        shape = ClawDimens.NestedShape,
        border = ClawDimens.HairlineBorder,
        modifier = Modifier.fillMaxSize()
    ) {
        SelectionContainer {
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun SourceBadge(source: String) {
    val isCodex = source.lowercase() == "codex"
    val bg = if (isCodex) ClawColors.BadgeCodexBg else ClawColors.BadgeAgyBg
    val border = if (isCodex) ClawColors.BadgeCodexBorder else ClawColors.BadgeAgyBorder
    val fg = if (isCodex) ClawColors.BadgeCodexText else ClawColors.BadgeAgyText
    val label = if (isCodex) "Codex" else "AGY"
    Surface(
        color = bg,
        contentColor = fg,
        shape = ClawDimens.BadgeShape,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SessionStatusDot(status: String) {
    val color = when (status) {
        "running" -> ClawColors.Success
        "waiting_approval" -> ClawColors.Warning
        "error" -> ClawColors.Error
        else -> ClawColors.TextMuted
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

fun statusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "waiting_approval" -> "等确认"
    "error" -> "报错"
    else -> "已完成"
}

fun formatShortTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    return if (diff < 24 * 3600 * 1000L && diff >= -60_000L) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun ModelPickerDialog(
    source: String,
    currentModelId: String,
    currentModelLabel: String,
    availableModels: List<ModelOptionUi>,
    desktopOnline: Boolean,
    onRefreshModels: () -> Unit,
    onSelect: (ModelOptionUi) -> Unit,
    onDismiss: () -> Unit,
) {
    var familyFilter by remember { mutableStateOf("ALL") }

    val categories = remember(source) {
        if (source == "codex") {
            listOf("ALL" to "全部")
        } else {
            listOf(
                "ALL" to "全部",
                "CARBON" to "4 Carbon",
                "ARGON" to "4 Argon",
                "NEON" to "4 Neon",
                "HYDROGEN" to "4 Hydrogen",
                "CLAUDE" to "Claude / GPT"
            )
        }
    }

    val filtered = remember(availableModels, familyFilter) {
        when (familyFilter) {
            "CARBON" -> availableModels.filter { it.label.contains("Carbon", ignoreCase = true) }
            "ARGON" -> availableModels.filter { it.label.contains("Argon", ignoreCase = true) }
            "NEON" -> availableModels.filter { it.label.contains("Neon", ignoreCase = true) }
            "HYDROGEN" -> availableModels.filter { it.label.contains("Hydrogen", ignoreCase = true) }
            "CLAUDE" -> availableModels.filter {
                it.label.contains("Claude", ignoreCase = true) ||
                    it.label.contains("GPT", ignoreCase = true) ||
                    it.label.contains("Flash", ignoreCase = true)
            }
            else -> availableModels
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = ClawDimens.CardShape,
            color = ClawColors.Surface,
            border = ClawDimens.HairlineBorder,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(540.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("切换任务模型", style = MaterialTheme.typography.titleMedium, color = ClawColors.TextPrimary)
                        Text(
                            text = "当前：${currentModelLabel.ifBlank { "默认模型" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClawColors.TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRefreshModels) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新模型列表", tint = ClawColors.TextSecondary)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary)
                        }
                    }
                }

                if (categories.size > 1) {
                    val chipColors = FilterChipDefaults.filterChipColors(
                        containerColor = ClawColors.SurfaceElevated,
                        labelColor = ClawColors.TextSecondary,
                        selectedContainerColor = ClawColors.Primary,
                        selectedLabelColor = ClawColors.OnPrimary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { (key, label) ->
                            FilterChip(
                                selected = familyFilter == key,
                                onClick = { familyFilter = key },
                                colors = chipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = familyFilter == key,
                                    borderColor = ClawColors.Border,
                                    selectedBorderColor = ClawColors.Primary
                                ),
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClawColors.Border, modifier = Modifier.padding(vertical = 4.dp))

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (availableModels.isEmpty()) "正在从桌面读取实时模型列表..." else "该分类下暂无模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClawColors.TextMuted
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onRefreshModels,
                                shape = ClawDimens.NestedShape,
                                border = ClawDimens.HairlineBorder
                            ) {
                                Text("重新拉取", color = ClawColors.TextPrimary)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(filtered, key = { idx, item -> "${item.modelId}-$idx" }) { _, item ->
                            val isSelected = item.modelId == currentModelId ||
                                (currentModelId.isBlank() && item.label.equals(currentModelLabel, ignoreCase = true))

                            Card(
                                shape = ClawDimens.NestedShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) ClawColors.AccentSoft else ClawColors.SurfaceElevated
                                ),
                                border = if (isSelected) BorderStroke(1.dp, ClawColors.AccentBorder) else ClawDimens.HairlineBorder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ClawDimens.NestedShape)
                                    .clickable(enabled = desktopOnline) { onSelect(item) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = ClawColors.TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.remainingPercent != null) {
                                            Surface(
                                                color = ClawColors.Surface,
                                                shape = ClawDimens.BadgeShape,
                                                border = ClawDimens.HairlineBorder
                                            ) {
                                                Text(
                                                    text = "余 ${item.remainingPercent}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClawColors.TextSecondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Text(
                                                text = "当前使用",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClawColors.Accent
                                            )
                                        } else if (item.tag.isNotBlank()) {
                                            Text(
                                                text = item.tag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClawColors.TextMuted
                                            )
                                        }
                                    }
                                    if (item.description.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClawColors.TextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
