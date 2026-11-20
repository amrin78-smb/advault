package com.advault.feature.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Raw brand palette. Screens should use [MaterialTheme.colorScheme] or [AdVaultTheme.status]. */
object AdVaultPalette {
    val Navy = Color(0xFF0A1128)
    val NavySurface = Color(0xFF111A36)
    val NavySurfaceHigh = Color(0xFF17224A)
    val NavySurfaceHighest = Color(0xFF1E2B5A)
    val NavyOutline = Color(0xFF2A3766)
    val Cobalt = Color(0xFF2F6BFF)
    val CobaltLight = Color(0xFF5C8DFF)
    val CobaltDeep = Color(0xFF1A47C2)
    val Teal = Color(0xFF2FD3A8)
    val Amber = Color(0xFFFFB547)
    val SoftRed = Color(0xFFFF6B6B)
    val MutedGrey = Color(0xFF8A94B3)
    val TextPrimary = Color(0xFFE8ECF8)
    val TextSecondary = Color(0xFFA9B3D1)

    // Light scheme counterparts.
    val LightBackground = Color(0xFFF5F7FD)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFE9EEFB)
    val LightText = Color(0xFF0E1630)
    val LightTextSecondary = Color(0xFF4A5577)
}

/** Semantic status colours that Material's scheme does not cover. */
@Immutable
data class StatusColors(
    val active: Color,
    val activeContainer: Color,
    val inactive: Color,
    val inactiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val info: Color,
    val infoContainer: Color,
    /** Background gradient for hero areas. */
    val heroGradient: Brush,
)

private val DarkStatus = StatusColors(
    active = AdVaultPalette.Teal,
    activeContainer = AdVaultPalette.Teal.copy(alpha = 0.16f),
    inactive = AdVaultPalette.MutedGrey,
    inactiveContainer = AdVaultPalette.MutedGrey.copy(alpha = 0.16f),
    warning = AdVaultPalette.Amber,
    warningContainer = AdVaultPalette.Amber.copy(alpha = 0.14f),
    error = AdVaultPalette.SoftRed,
    errorContainer = AdVaultPalette.SoftRed.copy(alpha = 0.14f),
    info = AdVaultPalette.CobaltLight,
    infoContainer = AdVaultPalette.Cobalt.copy(alpha = 0.16f),
    heroGradient = Brush.verticalGradient(listOf(AdVaultPalette.NavySurfaceHigh, AdVaultPalette.Navy)),
)

private val LightStatus = StatusColors(
    active = Color(0xFF0E8F6E),
    activeContainer = Color(0xFF0E8F6E).copy(alpha = 0.12f),
    inactive = Color(0xFF6B7591),
    inactiveContainer = Color(0xFF6B7591).copy(alpha = 0.12f),
    warning = Color(0xFFB26A00),
    warningContainer = Color(0xFFFFB547).copy(alpha = 0.22f),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFC62828).copy(alpha = 0.10f),
    info = AdVaultPalette.CobaltDeep,
    infoContainer = AdVaultPalette.Cobalt.copy(alpha = 0.10f),
    heroGradient = Brush.verticalGradient(listOf(AdVaultPalette.LightSurfaceHigh, AdVaultPalette.LightBackground)),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = AdVaultPalette.Cobalt,
    onPrimary = Color.White,
    primaryContainer = AdVaultPalette.CobaltDeep,
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = AdVaultPalette.CobaltLight,
    onSecondary = AdVaultPalette.Navy,
    secondaryContainer = AdVaultPalette.NavySurfaceHighest,
    onSecondaryContainer = AdVaultPalette.TextPrimary,
    tertiary = AdVaultPalette.Teal,
    onTertiary = AdVaultPalette.Navy,
    background = AdVaultPalette.Navy,
    onBackground = AdVaultPalette.TextPrimary,
    surface = AdVaultPalette.Navy,
    onSurface = AdVaultPalette.TextPrimary,
    surfaceVariant = AdVaultPalette.NavySurfaceHigh,
    onSurfaceVariant = AdVaultPalette.TextSecondary,
    surfaceContainerLowest = Color(0xFF070D20),
    surfaceContainerLow = Color(0xFF0E1630),
    surfaceContainer = AdVaultPalette.NavySurface,
    surfaceContainerHigh = AdVaultPalette.NavySurfaceHigh,
    surfaceContainerHighest = AdVaultPalette.NavySurfaceHighest,
    outline = AdVaultPalette.NavyOutline,
    outlineVariant = Color(0xFF1F2A52),
    error = AdVaultPalette.SoftRed,
    onError = AdVaultPalette.Navy,
    errorContainer = Color(0xFF5C1E26),
    onErrorContainer = Color(0xFFFFDAD9),
    inverseSurface = AdVaultPalette.TextPrimary,
    inverseOnSurface = AdVaultPalette.Navy,
    inversePrimary = AdVaultPalette.CobaltDeep,
    scrim = Color.Black,
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = AdVaultPalette.Cobalt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF00174D),
    secondary = AdVaultPalette.CobaltDeep,
    onSecondary = Color.White,
    secondaryContainer = AdVaultPalette.LightSurfaceHigh,
    onSecondaryContainer = AdVaultPalette.LightText,
    tertiary = Color(0xFF0E8F6E),
    background = AdVaultPalette.LightBackground,
    onBackground = AdVaultPalette.LightText,
    surface = AdVaultPalette.LightBackground,
    onSurface = AdVaultPalette.LightText,
    surfaceVariant = AdVaultPalette.LightSurfaceHigh,
    onSurfaceVariant = AdVaultPalette.LightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFE),
    surfaceContainer = AdVaultPalette.LightSurface,
    surfaceContainerHigh = AdVaultPalette.LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFDDE4F7),
    outline = Color(0xFFB7C0DB),
    outlineVariant = Color(0xFFD9DFEE),
    error = Color(0xFFC62828),
)

private val Base = Typography()
private val Sans = FontFamily.SansSerif

val AdVaultTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Sans),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Sans),
    bodySmall = Base.bodySmall.copy(fontFamily = Sans),
    labelLarge = Base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

/** Monospace style for domains and package names. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

val AdVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

/** Accessors for AdVault-specific theme values. */
object AdVaultTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

/**
 * AdVault theme. Dark-first: [darkTheme] defaults to true regardless of the system setting because the
 * product identity is dark. A light scheme is provided for completeness (and previews/tests).
 */
@Composable
fun AdVaultTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AdVaultTypography,
            shapes = AdVaultShapes,
            content = content,
        )
    }
}
