package com.hydra.panel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C8CFF),
    secondary = Color(0xFF5FD4A5),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1F2630),
    onBackground = Color(0xFFE6EAF0),
    onSurface = Color(0xFFE6EAF0),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D4DB7),
    secondary = Color(0xFF0E8A5F),
    tertiary = Color(0xFFB26A00),
)

@Composable
fun HydraTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

// Статусные цвета (единые для всех экранов)
val StatusGreen = Color(0xFF2ECC71)
val StatusRed = Color(0xFFE74C3C)
val StatusAmber = Color(0xFFF39C12)
val StatusGray = Color(0xFF8A94A6)
