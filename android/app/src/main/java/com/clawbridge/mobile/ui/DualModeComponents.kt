package com.clawbridge.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawbridge.mobile.data.LanPairingSnapshot
import com.clawbridge.mobile.net.ConnectionMode
import com.clawbridge.mobile.net.ConnectionUiState
import com.clawbridge.mobile.net.SocketStatus

/**
 * Prominent Top Dual-Mode Segmented Switch (`Height >= 56dp`, P4.4).
 * Pinned at the top of Home & Sessions tabs so the user sees the active mode + live status + RTT ms
 * at a glance and switches modes in a single tap.
 */
@Composable
internal fun DualModeTopSwitchBar(
    connState: ConnectionUiState,
    onSwitchMode: (ConnectionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLan = connState.connectionMode == ConnectionMode.LAN

    val (activeStatusText, activeDotColor) = resolveModeBadgeStatus(connState)

    Surface(
        color = ClawColors.ChromeBar,
        modifier = modifier
            .fillMaxWidth()
            .testTag("dual_mode_top_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = ClawColors.Canvas,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ClawColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = 58.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeSegmentButton(
                        title = "内网直连",
                        subtitle = if (isLan) activeStatusText else "局域网低延迟",
                        icon = Icons.Rounded.Wifi,
                        selected = isLan,
                        dotColor = if (isLan) activeDotColor else ClawColors.TextMuted.copy(alpha = 0.55f),
                        activeBg = ClawColors.Accent,
                        activeBorder = ClawColors.AccentBorder,
                        testTag = "mode_switch_lan",
                        onClick = { if (!isLan) onSwitchMode(ConnectionMode.LAN) },
                        modifier = Modifier.weight(1f)
                    )

                    ModeSegmentButton(
                        title = "外网中继",
                        subtitle = if (!isLan) activeStatusText else "跨网云端转发",
                        icon = Icons.Rounded.Public,
                        selected = !isLan,
                        dotColor = if (!isLan) activeDotColor else ClawColors.TextMuted.copy(alpha = 0.55f),
                        activeBg = ClawColors.Primary,
                        activeBorder = ClawColors.PrimaryBorder,
                        testTag = "mode_switch_wan",
                        onClick = { if (isLan) onSwitchMode(ConnectionMode.WAN) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(color = ClawColors.Border, thickness = 1.dp)
        }
    }
}

@Composable
private fun ModeSegmentButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    dotColor: Color,
    activeBg: Color,
    activeBorder: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (selected) activeBg.copy(alpha = if (ClawColors.isDark) 0.22f else 0.16f) else Color.Transparent
    val borderStroke = if (selected) BorderStroke(1.5.dp, activeBg) else BorderStroke(1.dp, Color.Transparent)
    val titleColor = if (selected) ClawColors.TextPrimary else ClawColors.TextSecondary
    val subColor = if (selected) ClawColors.TextPrimary.copy(alpha = 0.88f) else ClawColors.TextMuted
    val iconTint = if (selected) activeBg else ClawColors.TextMuted

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = borderStroke,
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        fontFamily = if (subtitle.endsWith("ms")) FontFamily.Monospace else FontFamily.Default
                    ),
                    color = subColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun resolveModeBadgeStatus(state: ConnectionUiState): Pair<String, Color> {
    val rttSuffix = state.latencyMs?.let { " · ${it}ms" } ?: ""
    return when {
        state.socketStatus == SocketStatus.CONNECTED && state.paired && state.desktopOnline ->
            ("已连接$rttSuffix") to ClawColors.Success

        state.socketStatus == SocketStatus.CONNECTED && state.paired && !state.desktopOnline ->
            "桌面离线" to ClawColors.Warning

        state.socketStatus == SocketStatus.CONNECTED && !state.paired ->
            "待配对" to ClawColors.Info

        state.socketStatus == SocketStatus.CONNECTING ->
            "连接中..." to ClawColors.Warning

        else ->
            "未连接" to ClawColors.Error
    }
}

/**
 * Mode Mismatch & Discovery Failure Card (P4.2 & P4.5).
 * Shown when LAN mode is selected on phone, but UDP discovery + known IP could not reach PC.
 * Provides:
 *   1. Explicit notice: 「电脑端未开启内网模式，请在电脑端切换」 + 「一键切回外网」 button (no silent fallback!)
 *   2. Explicit notice: 「未发现电脑，请确认同一 WiFi 或手动输入 IP」 + Manual IP input fallback
 */
@Composable
internal fun LanMismatchAndDiscoveryCard(
    connState: ConnectionUiState,
    onRetryDiscover: () -> Unit,
    onManualConnect: (String) -> Unit,
    onSwitchToWan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultIpSeed = connState.lanManualHost.ifBlank { connState.lanKnownIp }
    var manualIpInput by rememberSaveable(defaultIpSeed) { mutableStateOf(defaultIpSeed) }

    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = BorderStroke(1.dp, ClawColors.ErrorBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lan_mismatch_card")
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = ClawColors.Warning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "电脑端未开启内网模式，请在电脑端切换",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ClawColors.TextPrimary
                )
            }

            Text(
                text = "未发现电脑，请确认同一 WiFi 或手动输入 IP。",
                style = MaterialTheme.typography.bodySmall,
                color = ClawColors.TextSecondary
            )

            if (!connState.lastError.isNullOrBlank() &&
                !connState.lastError.startsWith("未发现电脑")
            ) {
                Text(
                    text = connState.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.Error
                )
            }

            // One-tap explicit switch back to WAN Relay (P4.5 — never automatic!)
            Button(
                onClick = onSwitchToWan,
                shape = ClawDimens.NestedShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawColors.Primary,
                    contentColor = ClawColors.OnPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_switch_back_wan")
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("一键切回外网", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }

            HorizontalDivider(color = ClawColors.Border)

            // Manual IP Input Fallback (P4.2)
            Text(
                text = "手动输入电脑局域网 IP 直连（发现被路由器隔离时使用）",
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextMuted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualIpInput,
                    onValueChange = { manualIpInput = it.trim() },
                    placeholder = { Text("例如 192.168.1.100", maxLines = 1) },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("lan_manual_ip_input")
                )
                FilledTonalButton(
                    onClick = { onManualConnect(manualIpInput) },
                    enabled = manualIpInput.isNotBlank(),
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ClawColors.AccentSoft,
                        contentColor = ClawColors.Accent
                    ),
                    border = BorderStroke(1.dp, ClawColors.AccentBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier.testTag("btn_lan_manual_connect")
                ) {
                    Text("直连", style = MaterialTheme.typography.labelMedium, softWrap = false)
                }
            }

            OutlinedButton(
                onClick = onRetryDiscover,
                shape = ClawDimens.NestedShape,
                border = ClawDimens.HairlineBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_lan_retry_scan")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新广播扫描电脑", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
            }
        }
    }
}

/**
 * Unpaired LAN 6-Digit Pairing Guide Card (P4.3).
 * Independent from WAN pairing — stores issued `lanToken` in Android Keystore.
 */
@Composable
internal fun LanPairingGuideCard(
    connState: ConnectionUiState,
    onSubmitLanPairCode: (String) -> Unit,
    onManualConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var codeInput by rememberSaveable { mutableStateOf("") }
    var showManualIp by rememberSaveable { mutableStateOf(false) }
    var manualIpInput by rememberSaveable(connState.lanManualHost, connState.lanKnownIp) {
        mutableStateOf(connState.lanManualHost.ifBlank { connState.lanKnownIp })
    }

    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = BorderStroke(1.dp, ClawColors.AccentBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lan_pairing_guide_card")
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = ClawColors.Accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "内网直连配对引导",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawColors.TextPrimary
                )
            }

            if (connState.socketStatus == SocketStatus.CONNECTING && connState.lanConnectedHost.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = ClawColors.Accent
                    )
                    Text(
                        text = connState.modeSwitchingHint ?: "正在局域网自动发现电脑...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClawColors.TextSecondary
                    )
                }
            } else if (connState.lanConnectedHost.isNotBlank()) {
                Surface(
                    color = ClawColors.AccentSoft,
                    shape = ClawDimens.NestedShape,
                    border = BorderStroke(1.dp, ClawColors.AccentBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Router,
                            contentDescription = null,
                            tint = ClawColors.Accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = buildString {
                                append("已发现电脑：")
                                if (connState.lanDiscoveredDeviceName.isNotBlank()) {
                                    append(connState.lanDiscoveredDeviceName)
                                    append(" · ")
                                }
                                append(connState.lanConnectedHost)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = ClawColors.TextPrimary
                        )
                    }
                }
            }

            Text(
                text = "请输入电脑桌面端「内网直连」面板显示的 6 位内网配对码（配对凭证加密存入系统 Keystore，与外网配对互不影响）：",
                style = MaterialTheme.typography.bodySmall,
                color = ClawColors.TextSecondary
            )

            OutlinedTextField(
                value = codeInput,
                onValueChange = { next -> codeInput = next.filter { it.isDigit() }.take(6) },
                label = { Text("6 位内网配对码") },
                placeholder = { Text("例如 805377") },
                singleLine = true,
                shape = ClawDimens.NestedShape,
                colors = clawTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lan_pair_code_input")
            )

            if (!connState.pairingError.isNullOrBlank()) {
                Text(
                    text = connState.pairingError,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawColors.Error,
                    modifier = Modifier.testTag("lan_pair_error_text")
                )
            }

            Button(
                onClick = { onSubmitLanPairCode(codeInput) },
                enabled = codeInput.length == 6 && !connState.pairingBusy,
                shape = ClawDimens.NestedShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawColors.Accent,
                    contentColor = ClawColors.OnAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_lan_pair_submit")
            ) {
                if (connState.pairingBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ClawColors.OnAccent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在完成内网配对...", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("完成内网配对", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { showManualIp = !showManualIp },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (showManualIp) "收起手动 IP 输入" else "手动指定电脑局域网 IP",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.Accent
                    )
                }
            }

            if (showManualIp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualIpInput,
                        onValueChange = { manualIpInput = it.trim() },
                        placeholder = { Text("例如 192.168.1.100:18791") },
                        singleLine = true,
                        shape = ClawDimens.NestedShape,
                        colors = clawTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { onManualConnect(manualIpInput) },
                        enabled = manualIpInput.isNotBlank(),
                        shape = ClawDimens.NestedShape
                    ) {
                        Text("定位电脑", style = MaterialTheme.typography.labelMedium, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Settings Card for LAN Direct Mode (Manual IP override & independent Keystore `lanToken` reset).
 */
@Composable
internal fun LanSettingsCard(
    lanSnapshot: LanPairingSnapshot,
    connState: ConnectionUiState,
    onConnectManualHost: (String) -> Unit,
    onUnpairLan: () -> Unit,
) {
    var manualHost by rememberSaveable(lanSnapshot.manualHostOverride, lanSnapshot.knownIp) {
        mutableStateOf(lanSnapshot.manualHostOverride.ifBlank { lanSnapshot.knownIp })
    }

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
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = ClawColors.Accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "内网直连 (LAN) 配置",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawColors.TextPrimary
                )
            }

            Text(
                text = "内网配对凭证独立加密保存在 Android Keystore 中，解除内网绑定不会影响外网中继配对。",
                style = MaterialTheme.typography.bodySmall,
                color = ClawColors.TextSecondary
            )

            SettingsMetaRow(
                label = "内网绑定状态",
                value = if (lanSnapshot.isPaired) {
                    "已配对 · ${lanSnapshot.bridgeDeviceName.ifBlank { lanSnapshot.bridgeDeviceId.ifBlank { "电脑桥端" } }}"
                } else {
                    "未配对"
                }
            )

            if (lanSnapshot.knownIp.isNotBlank()) {
                SettingsMetaRow(
                    label = "记住的局域网地址",
                    value = "${lanSnapshot.knownIp}:${lanSnapshot.knownPort}"
                )
            }

            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it.trim() },
                label = { Text("电脑局域网 IP（可选兜底，留空自动发现）") },
                placeholder = { Text("例如 192.168.1.100 或 192.168.1.5:18791") },
                singleLine = true,
                shape = ClawDimens.NestedShape,
                colors = clawTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_lan_ip_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onUnpairLan,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_settings_unpair_lan")
                ) {
                    Text("解除内网绑定", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                }

                Button(
                    onClick = { onConnectManualHost(manualHost) },
                    enabled = manualHost.isNotBlank(),
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Accent,
                        contentColor = ClawColors.OnAccent
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("切内网并直连 IP", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
