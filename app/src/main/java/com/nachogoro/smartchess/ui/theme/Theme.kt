package com.nachogoro.smartchess.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BoardGreen = Color(0xFF4C7A5C)
private val BoardGreenDark = Color(0xFF2E4B3F)
private val Parchment = Color(0xFFF0D9B5)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC7A2),
    onPrimary = Color(0xFF10281B),
    primaryContainer = BoardGreenDark,
    onPrimaryContainer = Parchment,
    secondary = Parchment,
    onSecondary = Color(0xFF3A2E1E),
)

private val LightColors = lightColorScheme(
    primary = BoardGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8D6),
    onPrimaryContainer = Color(0xFF0C2417),
    secondary = Color(0xFF8A6A43),
    onSecondary = Color.White,
)

@Composable
fun SmartChessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
