package com.clawbridge.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawbridge.mobile.data.PendingApprovalUi
import com.clawbridge.mobile.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun PinVerifyOrSetupDialog(
    hasPinConfigured: Boolean,
    promptTitle: String,
    promptSubtitle: String,
    settingsStore: SettingsStore,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pinInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClawColors.Surface,
        titleContentColor = ClawColors.TextPrimary,
        textContentColor = ClawColors.TextSecondary,
        shape = ClawDimens.CardShape,
        icon = {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = ClawColors.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        },
        title = {
            Text(
                text = if (hasPinConfigured) promptTitle else "首次安全校验：请先设置 PIN",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)) {
                Text(
                    text = if (hasPinConfigured) promptSubtitle
                    else "高风险操作必须经过 PIN 校验。请输入 4~6 位数字设置您的安全 PIN 并立即继续：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawColors.TextSecondary
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        pinInput = it.filter { c -> c.isDigit() }.take(6)
                        errorMsg = null
                    },
                    label = { Text(if (hasPinConfigured) "输入安全 PIN" else "设置 4~6 位数字 PIN") },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ClawColors.BorderStrong,
                        unfocusedBorderColor = ClawColors.Border,
                        focusedContainerColor = ClawColors.SurfaceElevated,
                        unfocusedContainerColor = ClawColors.SurfaceElevated,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg ?: "",
                        color = ClawColors.Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pinInput.length in 4..6,
                shape = ClawDimens.NestedShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClawColors.Primary,
                    contentColor = ClawColors.OnPrimary
                ),
                onClick = {
                    scope.launch {
                        if (!hasPinConfigured) {
                            settingsStore.setPin(pinInput)
                            onVerified()
                        } else {
                            if (settingsStore.verifyPin(pinInput)) {
                                onVerified()
                            } else {
                                errorMsg = "PIN 校验错误，请重试"
                                pinInput = ""
                            }
                        }
                    }
                }
            ) {
                Text(
                    text = if (hasPinConfigured) "校验并执行" else "保存并执行",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = ClawColors.TextSecondary)
            }
        }
    )
}

@Composable
fun PendingApprovalCard(
    item: PendingApprovalUi,
    desktopOnline: Boolean,
    onApproveClick: () -> Unit,
    onRejectClick: () -> Unit,
) {
    val isHigh = item.riskLevel == "high"
    val borderStroke = BorderStroke(1.dp, if (isHigh) ClawColors.ErrorBorder else ClawColors.WarningBorder)
    val badgeBg = if (isHigh) ClawColors.ErrorBg else ClawColors.WarningBg
    val badgeText = if (isHigh) ClawColors.Error else ClawColors.Warning

    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = borderStroke,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ClawDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    SourceBadge(item.source)
                    Spacer(Modifier.width(ClawDimens.SpaceSm))
                    Text(
                        text = item.project,
                        style = MaterialTheme.typography.titleSmall,
                        color = ClawColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = badgeBg,
                    contentColor = badgeText,
                    shape = ClawDimens.BadgeShape,
                    border = borderStroke
                ) {
                    Text(
                        text = if (isHigh) "高风险 · 需 PIN" else "低风险操作",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawColors.TextSecondary
                )
            }

            if (item.commandPreview.isNotBlank()) {
                Surface(
                    color = ClawColors.SurfaceCode,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.commandPreview,
                        color = ClawColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRejectClick,
                    enabled = desktopOnline,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = ClawColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("拒绝", style = MaterialTheme.typography.labelMedium, color = ClawColors.TextPrimary)
                }
                Spacer(Modifier.width(ClawDimens.SpaceSm))
                Button(
                    onClick = onApproveClick,
                    enabled = desktopOnline,
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isHigh) ClawColors.Error else ClawColors.Accent,
                        contentColor = ClawColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isHigh) "输 PIN 批准" else "批准执行",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
