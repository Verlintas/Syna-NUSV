package com.syna.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val SynaGreen = Color(0xFF00A05A)
val SynaGreenDark = Color(0xFF5ED6A0)
val SynaGreenContainer = Color(0xFFC9F5DE)
val SynaGreenContainerDark = Color(0xFF004D2B)
val SynaBackgroundDark = Color(0xFF111413)
val SynaSurfaceDark = Color(0xFF1A1E1C)
val SynaChatBubbleOut = Color(0xFF00A05A)
val SynaChatBubbleOutDark = Color(0xFF00683A)

private val LightColors = lightColorScheme(
    primary = SynaGreen,
    onPrimary = Color.White,
    primaryContainer = SynaGreenContainer,
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4C6354),
    background = Color(0xFFF8FBF8),
    surface = Color.White,
    surfaceVariant = Color(0xFFDEE5DF),
)

private val DarkColors = darkColorScheme(
    primary = SynaGreenDark,
    onPrimary = Color(0xFF00391E),
    primaryContainer = SynaGreenContainerDark,
    onPrimaryContainer = SynaGreenContainer,
    secondary = Color(0xFFB8CCBC),
    background = SynaBackgroundDark,
    surface = SynaSurfaceDark,
    surfaceVariant = Color(0xFF3F4942),
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

@Composable
fun SynaTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
