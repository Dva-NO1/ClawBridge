package com.clawbridge.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class ThemePresetOption(
    val id: String,
    val name: String,
    val previewColor: Color,
    val overrides: SectionColorOverrides,
)

internal val DARK_THEME_PRESETS = listOf(
    ThemePresetOption(
        id = "aurora_default",
        name = "极光晴空",
        previewColor = Color(0xFF6384FF),
        overrides = SectionColorOverrides(),
    ),
    ThemePresetOption(
        id = "sakura_night",
        name = "星夜樱粉",
        previewColor = Color(0xFFF472B6),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFF16111F.toInt(),
            surfaceArgb = 0xFF211A2E.toInt(),
            chromeBarArgb = 0xFF1A1426.toInt(),
            primaryArgb = 0xFFF472B6.toInt(),
            accentArgb = 0xFFFB7185.toInt(),
            userBubbleArgb = 0xFF4A2545.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "mint_forest",
        name = "森系薄荷",
        previewColor = Color(0xFF34D399),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFF0D1917.toInt(),
            surfaceArgb = 0xFF152623.toInt(),
            chromeBarArgb = 0xFF101F1D.toInt(),
            primaryArgb = 0xFF10B981.toInt(),
            accentArgb = 0xFF2DD4BF.toInt(),
            userBubbleArgb = 0xFF1B433B.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "twilight_violet",
        name = "暮光紫罗兰",
        previewColor = Color(0xFFA78BFA),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFF121024.toInt(),
            surfaceArgb = 0xFF1C1936.toInt(),
            chromeBarArgb = 0xFF16132C.toInt(),
            primaryArgb = 0xFF8B5CF6.toInt(),
            accentArgb = 0xFFC084FC.toInt(),
            userBubbleArgb = 0xFF372963.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "ocean_amber",
        name = "深海暖珀",
        previewColor = Color(0xFFF59E0B),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFF0E1624.toInt(),
            surfaceArgb = 0xFF172236.toInt(),
            chromeBarArgb = 0xFF121B2C.toInt(),
            primaryArgb = 0xFFF59E0B.toInt(),
            accentArgb = 0xFF38BDF8.toInt(),
            userBubbleArgb = 0xFF1E3A5F.toInt(),
        ),
    ),
)

internal val LIGHT_THEME_PRESETS = listOf(
    ThemePresetOption(
        id = "daylight_default",
        name = "晨曦晴空",
        previewColor = Color(0xFF4F6EF7),
        overrides = SectionColorOverrides(),
    ),
    ThemePresetOption(
        id = "peach_cream",
        name = "蜜桃奶油",
        previewColor = Color(0xFFEC4899),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFFFFF7F5.toInt(),
            surfaceArgb = 0xFFFFFFFF.toInt(),
            chromeBarArgb = 0xFFFFFCFB.toInt(),
            primaryArgb = 0xFFEC4899.toInt(),
            accentArgb = 0xFFF43F5E.toInt(),
            userBubbleArgb = 0xFFFFE4EC.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "mint_breeze",
        name = "薄荷清风",
        previewColor = Color(0xFF10B981),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFFF0FAF7.toInt(),
            surfaceArgb = 0xFFFFFFFF.toInt(),
            chromeBarArgb = 0xFFF8FEFC.toInt(),
            primaryArgb = 0xFF059669.toInt(),
            accentArgb = 0xFF0D9488.toInt(),
            userBubbleArgb = 0xFFD1FAE5.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "lavender_mist",
        name = "薰衣草雾",
        previewColor = Color(0xFF7C3AED),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFFF6F4FE.toInt(),
            surfaceArgb = 0xFFFFFFFF.toInt(),
            chromeBarArgb = 0xFFFAF9FF.toInt(),
            primaryArgb = 0xFF7C3AED.toInt(),
            accentArgb = 0xFF6366F1.toInt(),
            userBubbleArgb = 0xFFEDE9FE.toInt(),
        ),
    ),
    ThemePresetOption(
        id = "pure_paper",
        name = "极简纯白",
        previewColor = Color(0xFF2563EB),
        overrides = SectionColorOverrides(
            canvasArgb = 0xFFF8FAFC.toInt(),
            surfaceArgb = 0xFFFFFFFF.toInt(),
            chromeBarArgb = 0xFFFFFFFF.toInt(),
            primaryArgb = 0xFF1E293B.toInt(),
            accentArgb = 0xFF2563EB.toInt(),
            userBubbleArgb = 0xFFE2E8F0.toInt(),
        ),
    ),
)

/**
 * Settings Card: One-Tap Night/Day Switch + Curated Presets + Per-Section Interactive Color Picker
 */
@Composable
internal fun ThemeCustomizationCard(
    themeConfig: ThemeConfig,
    onSetDarkMode: (Boolean) -> Unit,
    onUpdateSectionColor: (ThemeSectionKey, Int?) -> Unit,
    onApplyPreset: (SectionColorOverrides) -> Unit,
    onResetCurrentMode: () -> Unit,
) {
    var editingSection by remember { mutableStateOf<ThemeSectionKey?>(null) }

    val basePalette = remember(themeConfig.isDarkMode) {
        if (themeConfig.isDarkMode) ClawPalette.auroraDarkBase() else ClawPalette.daylightBase()
    }
    val currentPalette = remember(themeConfig) { buildClawPalette(themeConfig) }
    val presets = if (themeConfig.isDarkMode) DARK_THEME_PRESETS else LIGHT_THEME_PRESETS

    editingSection?.let { section ->
        HsvSectionColorPickerDialog(
            section = section,
            initialColor = currentPalette.defaultSectionColor(section),
            defaultColor = basePalette.defaultSectionColor(section),
            baseConfig = themeConfig,
            onConfirmColor = { chosenArgb ->
                onUpdateSectionColor(section, chosenArgb)
                editingSection = null
            },
            onResetSection = {
                onUpdateSectionColor(section, null)
                editingSection = null
            },
            onDismiss = { editingSection = null }
        )
    }

    Card(
        shape = ClawDimens.CardShape,
        colors = CardDefaults.cardColors(containerColor = ClawColors.Surface),
        border = BorderStroke(1.dp, ClawColors.PrimaryBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_theme_card")
    ) {
        Column(
            modifier = Modifier.padding(ClawDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ClawDimens.SpaceMd)
        ) {
            // Header + Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = ClawColors.Accent,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "外观与主题配色",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (themeConfig.activeOverrides.hasAnyCustom()) {
                    TextButton(
                        onClick = onResetCurrentMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("btn_reset_theme_colors")
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            tint = ClawColors.Accent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("恢复默认", style = MaterialTheme.typography.labelSmall, color = ClawColors.Accent)
                    }
                }
            }

            // 1. One-Tap Night Mode vs Day Mode Segmented Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isDark = themeConfig.isDarkMode
                Surface(
                    color = if (isDark) ClawColors.Primary else ClawColors.SurfaceElevated,
                    shape = ClawDimens.NestedShape,
                    border = BorderStroke(1.dp, if (isDark) ClawColors.Primary else ClawColors.Border),
                    modifier = Modifier
                        .weight(1f)
                        .clip(ClawDimens.NestedShape)
                        .clickable { onSetDarkMode(true) }
                        .testTag("btn_mode_dark")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DarkMode,
                            contentDescription = null,
                            tint = if (isDark) ClawColors.OnPrimary else ClawColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "黑夜模式",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isDark) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (isDark) ClawColors.OnPrimary else ClawColors.TextSecondary
                        )
                    }
                }

                Surface(
                    color = if (!isDark) ClawColors.Primary else ClawColors.SurfaceElevated,
                    shape = ClawDimens.NestedShape,
                    border = BorderStroke(1.dp, if (!isDark) ClawColors.Primary else ClawColors.Border),
                    modifier = Modifier
                        .weight(1f)
                        .clip(ClawDimens.NestedShape)
                        .clickable { onSetDarkMode(false) }
                        .testTag("btn_mode_light")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LightMode,
                            contentDescription = null,
                            tint = if (!isDark) ClawColors.OnPrimary else ClawColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "白天模式",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (!isDark) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (!isDark) ClawColors.OnPrimary else ClawColors.TextSecondary
                        )
                    }
                }
            }

            // 2. Quick Theme Presets Strip
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (themeConfig.isDarkMode) "精选夜间主题预设" else "精选日间主题预设",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClawColors.TextMuted
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        val selected = themeConfig.activeOverrides == preset.overrides
                        Surface(
                            color = if (selected) ClawColors.PrimarySoft else ClawColors.SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) ClawColors.Primary else ClawColors.Border
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onApplyPreset(preset.overrides) }
                                .testTag("theme_preset_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preset.previewColor)
                                )
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = ClawColors.TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ClawColors.Border)

            // 3. Six Major App Sections — Tap Any Row to Open Full-Spectrum Color Picker
            Text(
                text = "各大板块独立取色（点击任一板块打开调色盘）",
                style = MaterialTheme.typography.labelSmall,
                color = ClawColors.TextMuted
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeSectionKey.entries.forEach { section ->
                    val activeColor = currentPalette.defaultSectionColor(section)
                    val isCustomized = themeConfig.activeOverrides[section] != null
                    Surface(
                        color = ClawColors.SurfaceElevated,
                        shape = ClawDimens.NestedShape,
                        border = BorderStroke(
                            1.dp,
                            if (isCustomized) ClawColors.PrimaryBorder else ClawColors.Border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ClawDimens.NestedShape)
                            .clickable { editingSection = section }
                            .testTag("section_color_row_${section.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                                    .border(1.5.dp, ClawColors.BorderStrong, CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp),
                                        color = ClawColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isCustomized) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "自定义",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = ClawColors.Accent,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                                Text(
                                    text = section.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClawColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = formatColorHex(activeColor),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ClawColors.TextSecondary
                            )
                            Icon(
                                imageVector = Icons.Rounded.ColorLens,
                                contentDescription = "选择颜色",
                                tint = ClawColors.Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Interactive 2D Saturation/Value + 360° Hue Spectrum Color Picker Dialog with Live App Mini-Preview.
 */
@Composable
internal fun HsvSectionColorPickerDialog(
    section: ThemeSectionKey,
    initialColor: Color,
    defaultColor: Color,
    baseConfig: ThemeConfig,
    onConfirmColor: (Int) -> Unit,
    onResetSection: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        HsvSectionColorPickerCard(
            section = section,
            initialColor = initialColor,
            defaultColor = defaultColor,
            baseConfig = baseConfig,
            onConfirmColor = onConfirmColor,
            onResetSection = onResetSection,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun HsvSectionColorPickerCard(
    section: ThemeSectionKey,
    initialColor: Color,
    defaultColor: Color,
    baseConfig: ThemeConfig,
    onConfirmColor: (Int) -> Unit,
    onResetSection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toArgb(), it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initHsv[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(initHsv[2]) }

    val pickedColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }
    var hexInput by remember(pickedColor) { mutableStateOf(formatColorHex(pickedColor)) }

    fun applyColorFromInt(argb: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        hexInput = String.format("#%06X", argb and 0xFFFFFF)
    }

    val previewConfig = remember(baseConfig, section, pickedColor) {
        val nextOverrides = baseConfig.activeOverrides.withColor(section, pickedColor.toArgb())
        if (baseConfig.isDarkMode) baseConfig.copy(darkOverrides = nextOverrides)
        else baseConfig.copy(lightOverrides = nextOverrides)
    }
    val livePreviewPalette = remember(previewConfig) { buildClawPalette(previewConfig) }

    val quickSwatches = remember(section, baseConfig.isDarkMode) {
        when (section) {
            ThemeSectionKey.CANVAS -> if (baseConfig.isDarkMode) {
                listOf(0xFF0E1320, 0xFF141418, 0xFF0D1917, 0xFF16111F, 0xFF121024, 0xFF0B192C, 0xFF1C1917, 0xFF111827)
            } else {
                listOf(0xFFF2F5FC, 0xFFFFFFFF, 0xFFFFF7F5, 0xFFF0FAF7, 0xFFF6F4FE, 0xFFFEFCE8, 0xFFF1F5F9, 0xFFFDF2F8)
            }
            ThemeSectionKey.SURFACE, ThemeSectionKey.CHROME_BAR -> if (baseConfig.isDarkMode) {
                listOf(0xFF161E31, 0xFF1E1E24, 0xFF152623, 0xFF211A2E, 0xFF1C1936, 0xFF13233A, 0xFF262320, 0xFF1F2937)
            } else {
                listOf(0xFFFFFFFF, 0xFFF8FAFC, 0xFFFFFCFB, 0xFFF5FBF9, 0xFFFAF8FF, 0xFFFFFBEB, 0xFFF1F5FD, 0xFFFFF1F2)
            }
            else -> listOf(
                0xFF6384FF, 0xFF3B82F6, 0xFF06B6D4, 0xFF2DD4BF,
                0xFF10B981, 0xFF8B5CF6, 0xFFA855F7, 0xFFEC4899,
                0xFFF43F5E, 0xFFF97316, 0xFFF59E0B, 0xFF234177
            )
        }.map { Color(it.toInt() or -0x1000000) }
    }

    Surface(
        shape = ClawDimens.CardShape,
        color = ClawColors.Surface,
        border = BorderStroke(1.dp, ClawColors.PrimaryBorder),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .testTag("hsv_color_picker_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自定义 · ${section.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawColors.TextPrimary
                    )
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.TextMuted
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = ClawColors.TextSecondary)
                }
            }

            // Live Miniature App Preview
            Surface(
                color = livePreviewPalette.canvas,
                shape = ClawDimens.NestedShape,
                border = BorderStroke(1.dp, livePreviewPalette.borderStrong),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(livePreviewPalette.chromeBar)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ClawBridge 实时预览",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = livePreviewPalette.textPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(livePreviewPalette.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "主按钮",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = livePreviewPalette.onPrimary
                            )
                        }
                    }
                    HorizontalDivider(color = livePreviewPalette.border, thickness = 0.5.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                color = livePreviewPalette.userBubbleBg,
                                shape = RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp),
                                border = BorderStroke(1.dp, livePreviewPalette.userBubbleBorder)
                            ) {
                                Text(
                                    text = "我的聊天气泡示例文字",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = livePreviewPalette.userBubbleText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Surface(
                            color = livePreviewPalette.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, livePreviewPalette.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "卡片与 AI 回复面板 · 文字清晰自适应",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = livePreviewPalette.textPrimary
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(livePreviewPalette.quotaTrack)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.76f)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(livePreviewPalette.healthyQuotaBrush)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2D Saturation-Value Color Pad
            val pureHueColor = remember(hue) {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(ClawDimens.NestedShape)
                    .border(1.dp, ClawColors.BorderStrong, ClawDimens.NestedShape)
                    .testTag("hsv_sat_val_pad")
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            saturation = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            value = (1f - offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
                            hexInput = String.format("#%06X", c and 0xFFFFFF)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            saturation = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            value = (1f - change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
                            hexInput = String.format("#%06X", c and 0xFFFFFF)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor))
                    )
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    )
                    val cx = saturation * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = 10.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 8.5.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // 360° Rainbow Hue Spectrum Bar
            val rainbowColors = remember {
                listOf(
                    Color(0xFFFF0000),
                    Color(0xFFFFFF00),
                    Color(0xFF00FF00),
                    Color(0xFF00FFFF),
                    Color(0xFF0000FF),
                    Color(0xFFFF00FF),
                    Color(0xFFFF0000),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, ClawColors.BorderStrong, CircleShape)
                    .testTag("hsv_hue_slider")
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            hue = ((offset.x / size.width.coerceAtLeast(1)) * 360f).coerceIn(0f, 360f)
                            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
                            hexInput = String.format("#%06X", c and 0xFFFFFF)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            hue = ((change.position.x / size.width.coerceAtLeast(1)) * 360f).coerceIn(0f, 360f)
                            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
                            hexInput = String.format("#%06X", c and 0xFFFFFF)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(brush = Brush.horizontalGradient(rainbowColors))
                    val hx = (hue / 360f) * size.width
                    drawCircle(
                        color = Color.White,
                        radius = 10.dp.toPx(),
                        center = Offset(hx, size.height / 2f),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }

            // Quick Swatches Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickSwatches.forEachIndexed { idx, sw ->
                    val isCurrent = (sw.toArgb() and 0xFFFFFF) == (pickedColor.toArgb() and 0xFFFFFF)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(sw)
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = if (isCurrent) ClawColors.Primary else ClawColors.BorderStrong,
                                shape = CircleShape
                            )
                            .clickable { applyColorFromInt(sw.toArgb()) }
                            .testTag("picker_swatch_$idx")
                    ) {
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (sw.luminance() > 0.5f) Color.Black else Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Hex Input + Reset Default Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pickedColor)
                        .border(1.dp, ClawColors.BorderStrong, RoundedCornerShape(10.dp))
                )
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { next ->
                        val filtered = next.uppercase().take(7)
                        hexInput = filtered
                        parseHexColorOrNull(filtered)?.let { parsed ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(parsed, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("HEX 色值") },
                    singleLine = true,
                    shape = ClawDimens.NestedShape,
                    colors = clawTextFieldColors(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("picker_hex_input")
                )
                OutlinedButton(
                    onClick = onResetSection,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("picker_reset_section_btn")
                ) {
                    Text(
                        text = "默认色 (${formatColorHex(defaultColor)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClawColors.TextSecondary
                    )
                }
            }

            // Confirm / Cancel Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = ClawDimens.NestedShape,
                    border = ClawDimens.HairlineBorder,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消", color = ClawColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = { onConfirmColor(pickedColor.toArgb()) },
                    shape = ClawDimens.NestedShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClawColors.Primary,
                        contentColor = ClawColors.OnPrimary
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("picker_confirm_btn")
                ) {
                    Text("应用该颜色", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
