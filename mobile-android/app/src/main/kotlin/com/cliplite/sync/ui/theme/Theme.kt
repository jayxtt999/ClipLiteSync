package com.cliplite.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 设计 token 实测自桌面端设置页（design/ClipLiteSync移动端界面设计方案-v1.html）
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EEFC),
    onPrimaryContainer = Color(0xFF1557B0),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF1B1F27),
    surface = Color.White,
    onSurface = Color(0xFF1B1F27),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF66707F),
    outline = Color(0xFFE4E8F0),
    error = Color(0xFFB91C1C),
    errorContainer = Color(0xFFFBE9E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AB3F5),
    onPrimary = Color(0xFF0E1B2C),
    primaryContainer = Color(0xFF1E3350),
    onPrimaryContainer = Color(0xFF9CC3F9),
    background = Color(0xFF111419),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF1B2028),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF232A34),
    onSurfaceVariant = Color(0xFF98A2B3),
    outline = Color(0xFF2A313C),
    error = Color(0xFFF08C8C),
    errorContainer = Color(0xFF33201F),
)

@Composable
fun ClipSyncTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
