package com.autonomi.examples.antdemo.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Autonomi palette, lifted verbatim from the desktop app
// (ant-ui tailwind.config.cjs + assets/css/main.css). blue/muted/status
// colors are identical across modes; bg/surface/border/text swap.
private val AntBlue = Color(0xFF4A9FE5)
private val AntMuted = Color(0xFF64748B)
private val AntSuccess = Color(0xFF22C55E)
private val AntError = Color(0xFFEF4444)

// Dark (default)
private val DarkBg = Color(0xFF0A0F1C)
private val DarkSurface = Color(0xFF141B2D)
private val DarkBorder = Color(0xFF1E2A3F)
private val DarkText = Color(0xFFE2E8F0)

// Light
private val LightBg = Color(0xFFF8FAFC)
private val LightSurface = Color(0xFFFFFFFF)
private val LightBorder = Color(0xFFCBD5E1)
private val LightText = Color(0xFF0F172A)

private val AntDarkColors = darkColorScheme(
    primary = AntBlue,
    onPrimary = Color.White,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = AntMuted,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = AntError,
    onError = Color.White,
    primaryContainer = AntBlue.copy(alpha = 0.12f),
    onPrimaryContainer = AntBlue,
)

private val AntLightColors = lightColorScheme(
    primary = AntBlue,
    onPrimary = Color.White,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurface,
    onSurfaceVariant = AntMuted,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = AntError,
    onError = Color.White,
    primaryContainer = AntBlue.copy(alpha = 0.12f),
    onPrimaryContainer = AntBlue,
)

/// Extra semantic colors not in Material's scheme (status dots).
object AntColors {
    val success = AntSuccess
    val muted = AntMuted
}

/// Theme state — mirrors the desktop `settingsStore.themeMode` ("dark" default),
/// persisted to SharedPreferences so it survives relaunch (the desktop persists
/// via its Tauri config).
object ThemeController {
    private const val PREFS = "ant_demo_prefs"
    private const val KEY_DARK = "dark_mode"
    var dark by mutableStateOf(true)
        private set

    fun load(context: Context) {
        dark = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)
    }

    fun setDark(context: Context, value: Boolean) {
        dark = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK, value).apply()
    }
}

@Composable
fun AntTheme(dark: Boolean = ThemeController.dark, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) AntDarkColors else AntLightColors,
        content = content,
    )
}
