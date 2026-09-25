package com.tgfinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ImdbYellow = Color(0xFFF5C518)
val ImdbYellowDark = Color(0xFFC9A20F)

private val DarkColors = darkColorScheme(
    primary = ImdbYellow,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3000),
    onPrimaryContainer = ImdbYellow,
    secondary = ImdbYellow,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    surfaceContainerLow = Color(0xFF141414),
    outline = Color(0xFF444444),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = ImdbYellowDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFFFF1B8),
    onPrimaryContainer = Color(0xFF3A3000),
    secondary = ImdbYellowDark,
    onSecondary = Color.Black,
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF555555),
)

enum class ThemeMode { DARK, LIGHT, SYSTEM }

@Composable
fun TgFinderTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
