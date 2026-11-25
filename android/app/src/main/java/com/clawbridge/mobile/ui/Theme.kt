package com.clawbridge.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Unified ClawBridge Design Tokens
 * Based on design-system/clawbridge/MASTER.md (ChatGPT Mobile Dark aesthetic:
 * calm matte charcoal surfaces, 1dp hairline borders, muted status colors, zero neon/gradients).
 */
object ClawColors {
    val Canvas = Color(0xFF141414)
    val Surface = Color(0xFF1E1E1E)
    val SurfaceElevated = Color(0xFF282828)
    val SurfaceCode = Color(0xFF111111)
    val SurfaceCodeHeader = Color(0xFF1A1A1A)

    val Border = Color(0xFF2E2E2E)
    val BorderStrong = Color(0xFF424242)

    val Primary = Color(0xFFECECEC)
    val OnPrimary = Color(0xFF141414)
    val Accent = Color(0xFF2EB88A)
    val AccentSoft = Color(0xFF172C25)
    val AccentBorder = Color(0xFF264D40)

    val TextPrimary = Color(0xFFECECEC)
    val TextSecondary = Color(0xFF9B9B9B)
    val TextMuted = Color(0xFF737373)

    val Success = Color(0xFF4ADE80)
    val SuccessBg = Color(0xFF162920)
    val SuccessBorder = Color(0xFF244736)

    val Warning = Color(0xFFF0B941)
    val WarningBg = Color(0xFF2A2315)
    val WarningBorder = Color(0xFF4A3D20)

    val Error = Color(0xFFF07178)
    val ErrorBg = Color(0xFF2D1B1E)
    val ErrorBorder = Color(0xFF522D33)

    val Info = Color(0xFF7CB7F5)
    val InfoBg = Color(0xFF1A2430)
    val InfoBorder = Color(0xFF2A3D52)

    val BadgeCodexBg = Color(0xFF252A33)
    val BadgeCodexBorder = Color(0xFF3A4252)
    val BadgeCodexText = Color(0xFFD6DEEB)

    val BadgeAgyBg = Color(0xFF182A25)
    val BadgeAgyBorder = Color(0xFF28483F)
    val BadgeAgyText = Color(0xFF83D9BE)
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

    val HairlineBorder = BorderStroke(1.dp, ClawColors.Border)
    val StrongBorder = BorderStroke(1.dp, ClawColors.BorderStrong)
}

private val ClawDarkScheme = darkColorScheme(
    primary = ClawColors.Primary,
    onPrimary = ClawColors.OnPrimary,
    primaryContainer = ClawColors.SurfaceElevated,
    onPrimaryContainer = ClawColors.TextPrimary,
    secondary = ClawColors.Accent,
    onSecondary = ClawColors.OnPrimary,
    secondaryContainer = ClawColors.SurfaceElevated,
    onSecondaryContainer = ClawColors.TextPrimary,
    tertiary = ClawColors.Accent,
    onTertiary = ClawColors.OnPrimary,
    tertiaryContainer = ClawColors.AccentSoft,
    onTertiaryContainer = ClawColors.TextPrimary,
    background = ClawColors.Canvas,
    onBackground = ClawColors.TextPrimary,
    surface = ClawColors.Surface,
    onSurface = ClawColors.TextPrimary,
    surfaceVariant = ClawColors.SurfaceElevated,
    onSurfaceVariant = ClawColors.TextSecondary,
    outline = ClawColors.BorderStrong,
    outlineVariant = ClawColors.Border,
    error = ClawColors.Error,
    onError = ClawColors.OnPrimary,
    errorContainer = ClawColors.ErrorBg,
    onErrorContainer = ClawColors.Error,
)

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
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClawDarkScheme,
        typography = ClawTypography,
        content = content
    )
}
