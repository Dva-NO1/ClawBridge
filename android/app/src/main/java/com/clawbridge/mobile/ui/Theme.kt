package com.clawbridge.mobile.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Six major visual sections ("大板块") that users can customize via the interactive HSV Color Picker.
 */
enum class ThemeSectionKey(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    CANVAS("canvas", "全局背景底色", "首页、会话列表与对话页底层大背景"),
    SURFACE("surface", "卡片与消息面板", "配额卡、AI 回复卡片、弹窗与设置面板"),
    CHROME_BAR("chromeBar", "顶栏与底栏导航", "顶部标题栏、底部导航栏与聊天输入栏"),
    PRIMARY("primary", "主按钮与选中态", "主操作按钮、新建项目与选中筛选标签"),
    ACCENT("accent", "点睛强调与进度条", "配额进度条、实时速率与高亮状态点缀"),
    USER_BUBBLE("userBubble", "我的聊天气泡", "对话窗口中用户发送的消息气泡底色"),
}

@Immutable
data class SectionColorOverrides(
    val canvasArgb: Int? = null,
    val surfaceArgb: Int? = null,
    val chromeBarArgb: Int? = null,
    val primaryArgb: Int? = null,
    val accentArgb: Int? = null,
    val userBubbleArgb: Int? = null,
) {
    operator fun get(key: ThemeSectionKey): Int? = when (key) {
        ThemeSectionKey.CANVAS -> canvasArgb
        ThemeSectionKey.SURFACE -> surfaceArgb
        ThemeSectionKey.CHROME_BAR -> chromeBarArgb
        ThemeSectionKey.PRIMARY -> primaryArgb
        ThemeSectionKey.ACCENT -> accentArgb
        ThemeSectionKey.USER_BUBBLE -> userBubbleArgb
    }

    fun withColor(key: ThemeSectionKey, argb: Int?): SectionColorOverrides = when (key) {
        ThemeSectionKey.CANVAS -> copy(canvasArgb = argb)
        ThemeSectionKey.SURFACE -> copy(surfaceArgb = argb)
        ThemeSectionKey.CHROME_BAR -> copy(chromeBarArgb = argb)
        ThemeSectionKey.PRIMARY -> copy(primaryArgb = argb)
        ThemeSectionKey.ACCENT -> copy(accentArgb = argb)
        ThemeSectionKey.USER_BUBBLE -> copy(userBubbleArgb = argb)
    }

    fun hasAnyCustom(): Boolean =
        canvasArgb != null || surfaceArgb != null || chromeBarArgb != null ||
            primaryArgb != null || accentArgb != null || userBubbleArgb != null

    fun serialize(): String {
        val parts = mutableListOf<String>()
        ThemeSectionKey.entries.forEach { k ->
            val v = this[k]
            if (v != null) {
                parts += "${k.id}=${String.format("#%06X", v and 0xFFFFFF)}"
            }
        }
        return parts.joinToString(";")
    }

    companion object {
        fun deserialize(raw: String): SectionColorOverrides {
            if (raw.isBlank()) return SectionColorOverrides()
            var out = SectionColorOverrides()
            raw.split(";").forEach { token ->
                val eq = token.indexOf('=')
                if (eq > 0) {
                    val k = token.substring(0, eq).trim()
                    val hex = token.substring(eq + 1).trim()
                    val key = ThemeSectionKey.entries.find { it.id == k }
                    val parsed = parseHexColorOrNull(hex)
                    if (key != null && parsed != null) {
                        out = out.withColor(key, parsed)
                    }
                }
            }
            return out
        }
    }
}

@Immutable
data class ThemeConfig(
    val isDarkMode: Boolean = true,
    val darkOverrides: SectionColorOverrides = SectionColorOverrides(),
    val lightOverrides: SectionColorOverrides = SectionColorOverrides(),
) {
    val activeOverrides: SectionColorOverrides
        get() = if (isDarkMode) darkOverrides else lightOverrides
}

internal fun parseHexColorOrNull(hexRaw: String): Int? {
    val clean = hexRaw.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    val parsed = clean.toLongOrNull(16) ?: return null
    return if (clean.length == 6) {
        (0xFF000000L or parsed).toInt()
    } else {
        parsed.toInt()
    }
}

internal fun formatColorHex(color: Color): String =
    String.format("#%06X", color.toArgb() and 0xFFFFFF)

private fun shiftColorHue(color: Color, deltaDegrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = ((hsv[0] + deltaDegrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Immutable
data class ClawPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val chromeBar: Color,
    val surfaceElevated: Color,
    val surfaceCode: Color,
    val surfaceCodeHeader: Color,
    val border: Color,
    val borderStrong: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val primaryBorder: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val secondaryAccent: Color,
    val secondarySoft: Color,
    val userBubbleBg: Color,
    val userBubbleBorder: Color,
    val userBubbleText: Color,
    val runningRowBg: Color,
    val quotaTrack: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val success: Color,
    val successBg: Color,
    val successBorder: Color,
    val warning: Color,
    val warningBg: Color,
    val warningBorder: Color,
    val error: Color,
    val errorBg: Color,
    val errorBorder: Color,
    val info: Color,
    val infoBg: Color,
    val infoBorder: Color,
    val badgeCodexBg: Color,
    val badgeCodexBorder: Color,
    val badgeCodexText: Color,
    val badgeAgyBg: Color,
    val badgeAgyBorder: Color,
    val badgeAgyText: Color,
) {
    val primaryGradientBrush: Brush
        get() = Brush.horizontalGradient(listOf(heroGradientStart, heroGradientEnd))

    val healthyQuotaBrush: Brush
        get() = Brush.horizontalGradient(listOf(accent, success))

    fun defaultSectionColor(key: ThemeSectionKey): Color = when (key) {
        ThemeSectionKey.CANVAS -> canvas
        ThemeSectionKey.SURFACE -> surface
        ThemeSectionKey.CHROME_BAR -> chromeBar
        ThemeSectionKey.PRIMARY -> primary
        ThemeSectionKey.ACCENT -> accent
        ThemeSectionKey.USER_BUBBLE -> userBubbleBg
    }

    fun toMaterialColorScheme(): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primarySoft,
            onPrimaryContainer = textPrimary,
            secondary = accent,
            onSecondary = if (accent.luminance() > 0.45f) Color(0xFF06201D) else Color.White,
            secondaryContainer = surfaceElevated,
            onSecondaryContainer = textPrimary,
            tertiary = secondaryAccent,
            onTertiary = Color(0xFF161032),
            tertiaryContainer = accentSoft,
            onTertiaryContainer = textPrimary,
            background = canvas,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = borderStrong,
            outlineVariant = border,
            error = error,
            onError = Color.White,
            errorContainer = errorBg,
            onErrorContainer = error,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primarySoft,
            onPrimaryContainer = textPrimary,
            secondary = accent,
            onSecondary = if (accent.luminance() > 0.48f) Color(0xFF06201D) else Color.White,
            secondaryContainer = surfaceElevated,
            onSecondaryContainer = textPrimary,
            tertiary = secondaryAccent,
            onTertiary = Color.White,
            tertiaryContainer = accentSoft,
            onTertiaryContainer = textPrimary,
            background = canvas,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = borderStrong,
            outlineVariant = border,
            error = error,
            onError = Color.White,
            errorContainer = errorBg,
            onErrorContainer = error,
        )
    }

    companion object {
        fun auroraDarkBase() = ClawPalette(
            isDark = true,
            canvas = Color(0xFF0E1320),
            surface = Color(0xFF161E31),
            chromeBar = Color(0xFF12192B),
            surfaceElevated = Color(0xFF202B44),
            surfaceCode = Color(0xFF0A0E18),
            surfaceCodeHeader = Color(0xFF131A2B),
            border = Color(0xFF293757),
            borderStrong = Color(0xFF3F5585),
            primary = Color(0xFF6384FF),
            onPrimary = Color(0xFFFFFFFF),
            primarySoft = Color(0xFF1D2B53),
            primaryBorder = Color(0xFF3B5598),
            heroGradientStart = Color(0xFF5B8CFF),
            heroGradientEnd = Color(0xFF8B6EFF),
            accent = Color(0xFF2DD4BF),
            accentSoft = Color(0xFF112F35),
            accentBorder = Color(0xFF215B63),
            secondaryAccent = Color(0xFFA78BFA),
            secondarySoft = Color(0xFF221E42),
            userBubbleBg = Color(0xFF234177),
            userBubbleBorder = Color(0xFF3B64AD),
            userBubbleText = Color(0xFFF4F7FE),
            runningRowBg = Color(0xFF11282B),
            quotaTrack = Color(0xFF23304C),
            textPrimary = Color(0xFFF4F7FE),
            textSecondary = Color(0xFFA6B4D0),
            textMuted = Color(0xFF7384A6),
            success = Color(0xFF34D399),
            successBg = Color(0xFF112C27),
            successBorder = Color(0xFF21574B),
            warning = Color(0xFFFBBF24),
            warningBg = Color(0xFF2C2312),
            warningBorder = Color(0xFF57441E),
            error = Color(0xFFFB7185),
            errorBg = Color(0xFF301824),
            errorBorder = Color(0xFF5E2B42),
            info = Color(0xFF60A5FA),
            infoBg = Color(0xFF152642),
            infoBorder = Color(0xFF2B4C7E),
            badgeCodexBg = Color(0xFF1E2749),
            badgeCodexBorder = Color(0xFF485CA8),
            badgeCodexText = Color(0xFFC7D2FE),
            badgeAgyBg = Color(0xFF113231),
            badgeAgyBorder = Color(0xFF256B65),
            badgeAgyText = Color(0xFF6EE7B7),
        )

        fun daylightBase() = ClawPalette(
            isDark = false,
            canvas = Color(0xFFF2F5FC),
            surface = Color(0xFFFFFFFF),
            chromeBar = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFEAF0FA),
            surfaceCode = Color(0xFFF4F7FC),
            surfaceCodeHeader = Color(0xFFE6ECF8),
            border = Color(0xFFDCE4F2),
            borderStrong = Color(0xFFB8C7E2),
            primary = Color(0xFF4F6EF7),
            onPrimary = Color(0xFFFFFFFF),
            primarySoft = Color(0xFFE4EAFF),
            primaryBorder = Color(0xFFB7C7FF),
            heroGradientStart = Color(0xFF4F6EF7),
            heroGradientEnd = Color(0xFF7C5CFF),
            accent = Color(0xFF0D9488),
            accentSoft = Color(0xFFE0F7F4),
            accentBorder = Color(0xFF99E2D8),
            secondaryAccent = Color(0xFF6D4AFF),
            secondarySoft = Color(0xFFEDE9FE),
            userBubbleBg = Color(0xFFDCE8FF),
            userBubbleBorder = Color(0xFFB2CCFF),
            userBubbleText = Color(0xFF142A56),
            runningRowBg = Color(0xFFE8FBF4),
            quotaTrack = Color(0xFFE1E8F5),
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF475569),
            textMuted = Color(0xFF64748B),
            success = Color(0xFF059669),
            successBg = Color(0xFFECFDF5),
            successBorder = Color(0xFFA7F3D0),
            warning = Color(0xFFD97706),
            warningBg = Color(0xFFFFFBEB),
            warningBorder = Color(0xFFFDE68A),
            error = Color(0xFFE11D48),
            errorBg = Color(0xFFFFF1F2),
            errorBorder = Color(0xFFFECDD3),
            info = Color(0xFF2563EB),
            infoBg = Color(0xFFEFF6FF),
            infoBorder = Color(0xFFBFDBFE),
            badgeCodexBg = Color(0xFFEEF2FF),
            badgeCodexBorder = Color(0xFFC7D2FE),
            badgeCodexText = Color(0xFF4338CA),
            badgeAgyBg = Color(0xFFECFDF5),
            badgeAgyBorder = Color(0xFFA7F3D0),
            badgeAgyText = Color(0xFF047857),
        )
    }
}

fun buildClawPalette(config: ThemeConfig): ClawPalette {
    val base = if (config.isDarkMode) ClawPalette.auroraDarkBase() else ClawPalette.daylightBase()
    val ov = config.activeOverrides
    if (!ov.hasAnyCustom()) return base

    val canvas = ov.canvasArgb?.let { Color(it) } ?: base.canvas
    val surface = ov.surfaceArgb?.let { Color(it) } ?: base.surface
    val surfaceIsLight = surface.luminance() > 0.48f

    val chromeBar = ov.chromeBarArgb?.let { Color(it) }
        ?: if (ov.surfaceArgb != null || ov.canvasArgb != null) lerp(canvas, surface, 0.65f) else base.chromeBar

    val surfaceElevated = if (ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF1E293B), 0.06f)
        else lerp(surface, Color.White, 0.08f)
    } else base.surfaceElevated

    val surfaceCode = if (ov.canvasArgb != null || ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF0F172A), 0.045f)
        else lerp(canvas, Color.Black, 0.25f)
    } else base.surfaceCode

    val surfaceCodeHeader = if (ov.canvasArgb != null || ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF0F172A), 0.09f)
        else lerp(surfaceCode, Color.White, 0.06f)
    } else base.surfaceCodeHeader

    val border = if (ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF0F172A), 0.13f)
        else lerp(surface, Color.White, 0.13f)
    } else base.border

    val borderStrong = if (ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF0F172A), 0.25f)
        else lerp(surface, Color.White, 0.25f)
    } else base.borderStrong

    val quotaTrack = if (ov.surfaceArgb != null) {
        if (surfaceIsLight) lerp(surface, Color(0xFF0F172A), 0.10f)
        else lerp(surface, Color.White, 0.10f)
    } else base.quotaTrack

    // Auto-flip ink colors if the user picked a light surface in Dark Mode or dark surface in Light Mode
    val textPrimary: Color
    val textSecondary: Color
    val textMuted: Color
    if (surfaceIsLight == !base.isDark) {
        textPrimary = base.textPrimary
        textSecondary = base.textSecondary
        textMuted = base.textMuted
    } else if (surfaceIsLight) {
        textPrimary = Color(0xFF111827)
        textSecondary = Color(0xFF475569)
        textMuted = Color(0xFF64748B)
    } else {
        textPrimary = Color(0xFFF4F7FE)
        textSecondary = Color(0xFFA6B4D0)
        textMuted = Color(0xFF7384A6)
    }

    val primary = ov.primaryArgb?.let { Color(it) } ?: base.primary
    val onPrimary = if (primary.luminance() > 0.48f) Color(0xFF0F172A) else Color.White
    val primarySoft = if (ov.primaryArgb != null || ov.surfaceArgb != null) {
        lerp(surface, primary, if (surfaceIsLight) 0.15f else 0.24f)
    } else base.primarySoft
    val primaryBorder = if (ov.primaryArgb != null || ov.surfaceArgb != null) {
        lerp(surface, primary, 0.46f)
    } else base.primaryBorder
    val heroStart = if (ov.primaryArgb != null) primary else base.heroGradientStart
    val heroEnd = if (ov.primaryArgb != null) shiftColorHue(primary, 22f) else base.heroGradientEnd

    val accent = ov.accentArgb?.let { Color(it) } ?: base.accent
    val accentSoft = if (ov.accentArgb != null || ov.surfaceArgb != null) {
        lerp(surface, accent, if (surfaceIsLight) 0.14f else 0.22f)
    } else base.accentSoft
    val accentBorder = if (ov.accentArgb != null || ov.surfaceArgb != null) {
        lerp(surface, accent, 0.45f)
    } else base.accentBorder

    val userBubbleBg = ov.userBubbleArgb?.let { Color(it) } ?: base.userBubbleBg
    val bubbleIsLight = userBubbleBg.luminance() > 0.48f
    val userBubbleText = if (ov.userBubbleArgb != null) {
        if (bubbleIsLight) Color(0xFF111827) else Color(0xFFF8FAFC)
    } else base.userBubbleText
    val userBubbleBorder = if (ov.userBubbleArgb != null) {
        lerp(userBubbleBg, if (bubbleIsLight) Color(0xFF0F172A) else Color.White, 0.18f)
    } else base.userBubbleBorder

    val runningRowBg = if (ov.accentArgb != null || ov.surfaceArgb != null) {
        lerp(canvas, accent, if (surfaceIsLight) 0.10f else 0.14f)
    } else base.runningRowBg

    return base.copy(
        isDark = !surfaceIsLight,
        canvas = canvas,
        surface = surface,
        chromeBar = chromeBar,
        surfaceElevated = surfaceElevated,
        surfaceCode = surfaceCode,
        surfaceCodeHeader = surfaceCodeHeader,
        border = border,
        borderStrong = borderStrong,
        primary = primary,
        onPrimary = onPrimary,
        primarySoft = primarySoft,
        primaryBorder = primaryBorder,
        heroGradientStart = heroStart,
        heroGradientEnd = heroEnd,
        accent = accent,
        accentSoft = accentSoft,
        accentBorder = accentBorder,
        userBubbleBg = userBubbleBg,
        userBubbleBorder = userBubbleBorder,
        userBubbleText = userBubbleText,
        runningRowBg = runningRowBg,
        quotaTrack = quotaTrack,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
    )
}

val LocalClawPalette = staticCompositionLocalOf { ClawPalette.auroraDarkBase() }

/**
 * Reactive ClawBridge Design Tokens.
 * Backed by Compose Snapshot State so every screen recomposes instantaneously when toggling
 * Night/Day mode or picking custom section colors.
 */
object ClawColors {
    internal var activePalette by mutableStateOf(ClawPalette.auroraDarkBase())

    val isDark: Boolean get() = activePalette.isDark
    val Canvas: Color get() = activePalette.canvas
    val Surface: Color get() = activePalette.surface
    val ChromeBar: Color get() = activePalette.chromeBar
    val SurfaceElevated: Color get() = activePalette.surfaceElevated
    val SurfaceCode: Color get() = activePalette.surfaceCode
    val SurfaceCodeHeader: Color get() = activePalette.surfaceCodeHeader

    val Border: Color get() = activePalette.border
    val BorderStrong: Color get() = activePalette.borderStrong

    val Primary: Color get() = activePalette.primary
    val OnPrimary: Color get() = activePalette.onPrimary
    val PrimarySoft: Color get() = activePalette.primarySoft
    val PrimaryBorder: Color get() = activePalette.primaryBorder

    val HeroGradientStart: Color get() = activePalette.heroGradientStart
    val HeroGradientEnd: Color get() = activePalette.heroGradientEnd

    val Accent: Color get() = activePalette.accent
    val OnAccent: Color get() = if (activePalette.accent.luminance() > 0.45f) Color(0xFF06201D) else Color.White
    val AccentSoft: Color get() = activePalette.accentSoft
    val AccentBorder: Color get() = activePalette.accentBorder

    val SecondaryAccent: Color get() = activePalette.secondaryAccent
    val SecondarySoft: Color get() = activePalette.secondarySoft

    val UserBubbleBg: Color get() = activePalette.userBubbleBg
    val UserBubbleBorder: Color get() = activePalette.userBubbleBorder
    val UserBubbleText: Color get() = activePalette.userBubbleText

    val RunningRowBg: Color get() = activePalette.runningRowBg
    val QuotaTrack: Color get() = activePalette.quotaTrack

    val TextPrimary: Color get() = activePalette.textPrimary
    val TextSecondary: Color get() = activePalette.textSecondary
    val TextMuted: Color get() = activePalette.textMuted

    val Success: Color get() = activePalette.success
    val SuccessBg: Color get() = activePalette.successBg
    val SuccessBorder: Color get() = activePalette.successBorder

    val Warning: Color get() = activePalette.warning
    val WarningBg: Color get() = activePalette.warningBg
    val WarningBorder: Color get() = activePalette.warningBorder

    val Error: Color get() = activePalette.error
    val ErrorBg: Color get() = activePalette.errorBg
    val ErrorBorder: Color get() = activePalette.errorBorder

    val Info: Color get() = activePalette.info
    val InfoBg: Color get() = activePalette.infoBg
    val InfoBorder: Color get() = activePalette.infoBorder

    val BadgeCodexBg: Color get() = activePalette.badgeCodexBg
    val BadgeCodexBorder: Color get() = activePalette.badgeCodexBorder
    val BadgeCodexText: Color get() = activePalette.badgeCodexText

    val BadgeAgyBg: Color get() = activePalette.badgeAgyBg
    val BadgeAgyBorder: Color get() = activePalette.badgeAgyBorder
    val BadgeAgyText: Color get() = activePalette.badgeAgyText

    val PrimaryGradientBrush: Brush get() = activePalette.primaryGradientBrush
    val HealthyQuotaBrush: Brush get() = activePalette.healthyQuotaBrush
}

object ClawDimens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp

    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
    val SectionGap = 16.dp
    val ItemGap = 10.dp

    val CardShape = RoundedCornerShape(16.dp)
    val NestedShape = RoundedCornerShape(12.dp)
    val BubbleShape = RoundedCornerShape(18.dp)
    val PillShape = RoundedCornerShape(22.dp)
    val BadgeShape = RoundedCornerShape(6.dp)

    val HairlineBorder: BorderStroke get() = BorderStroke(1.dp, ClawColors.Border)
    val StrongBorder: BorderStroke get() = BorderStroke(1.dp, ClawColors.BorderStrong)
}

private val ClawTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun ClawBridgeTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val resolvedPalette = remember(themeConfig) { buildClawPalette(themeConfig) }
    ClawColors.activePalette = resolvedPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                val useDarkIcons = resolvedPalette.chromeBar.luminance() > 0.48f
                controller.isAppearanceLightStatusBars = useDarkIcons
                controller.isAppearanceLightNavigationBars = useDarkIcons
            }
        }
    }

    CompositionLocalProvider(LocalClawPalette provides resolvedPalette) {
        MaterialTheme(
            colorScheme = resolvedPalette.toMaterialColorScheme(),
            typography = ClawTypography,
            content = content,
        )
    }
}
