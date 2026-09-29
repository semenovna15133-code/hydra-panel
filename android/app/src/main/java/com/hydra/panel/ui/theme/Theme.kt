package com.hydra.panel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Палитра «Neon Abyss»: глубокий космический фон + кибер-акценты ──
val Neon = Color(0xFF4DE8C2)        // мятный неон (primary)
val ElectricViolet = Color(0xFF8B7CF6) // фиолет (secondary)
val CyberBlue = Color(0xFF3DA5FF)   // голубой (tertiary / ссылки)
val DeepBg = Color(0xFF070B12)      // почти чёрный с синевой
val DeepSurface = Color(0xFF0D1420) // поверхности
val DeepSurfaceHi = Color(0xFF14202F) // приподнятые поверхности
val TextPrimary = Color(0xFFEAF2FA)
val TextSecondary = Color(0xFF7E93AC)

private val DarkColors = darkColorScheme(
    primary = Neon,
    onPrimary = Color(0xFF00281C),
    primaryContainer = Color(0xFF0F3B31),
    onPrimaryContainer = Color(0xFFB6F5E0),
    secondary = ElectricViolet,
    onSecondary = Color(0xFF1A1440),
    secondaryContainer = Color(0xFF2A2450),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = CyberBlue,
    onTertiary = Color(0xFF04213A),
    tertiaryContainer = Color(0xFF123A5C),
    onTertiaryContainer = Color(0xFFBFE3FF),
    background = DeepBg,
    onBackground = TextPrimary,
    surface = DeepSurface,
    onSurface = TextPrimary,
    surfaceVariant = DeepSurfaceHi,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF2A3A50),
    outlineVariant = Color(0xFF1B2838),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF4A1A1A),
    onErrorContainer = Color(0xFFFFC9C9),
    inverseSurface = Color(0xFFE8EDF4),
    inverseOnSurface = Color(0xFF10161F),
    scrim = Color(0xCC04070C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F5EA),
    secondary = Color(0xFF5A4FCF),
    tertiary = Color(0xFF0061A4),
    background = Color(0xFFF3F7FB),
    onBackground = Color(0xFF10161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10161F),
    surfaceVariant = Color(0xFFE2EAF2),
    onSurfaceVariant = Color(0xFF4A5A6E),
    outline = Color(0xFF8FA0B3),
)

val HydraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val HydraTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, color = TextSecondary),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

@Composable
fun HydraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = HydraShapes,
        typography = HydraTypography,
        content = content,
    )
}

// Статусные цвета (единые для всех экранов)
val StatusGreen = Color(0xFF34E3A1)
val StatusRed = Color(0xFFFF5C5C)
val StatusAmber = Color(0xFFFFB74D)
val StatusGray = Color(0xFF7E93AC)
