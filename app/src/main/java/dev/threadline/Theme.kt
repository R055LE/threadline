package dev.threadline

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    background = Color(0xFF0F1512),
    surface = Color(0xFF161D19),
    primary = Color(0xFF35D07F),
    onBackground = Color(0xFFD8F5E2),
    onSurface = Color(0xFFD8F5E2),
    error = Color(0xFFFF5449),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF2F0EC),
    surface = Color(0xFFFFFFFF),
    primary = Color(0xFF4E8B5C),
    onBackground = Color(0xFF2A2E29),
    onSurface = Color(0xFF2A2E29),
    error = Color(0xFFB3261E),
)

@Composable
fun ThreadlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
