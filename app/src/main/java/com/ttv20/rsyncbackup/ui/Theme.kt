package com.ttv20.rsyncbackup.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ttv20.rsyncbackup.model.ThemePreference

val SuccessColor = Color(0xFF2E7D32)
val WarningColor = Color(0xFFB26A00)
val DestructiveColor = Color(0xFFB3261E)
val RouteColor = Color(0xFF006A6A)
val LogSurfaceLight = Color(0xFFF1F5F3)
val LogSurfaceDark = Color(0xFF171D1B)

private val RsyncLightColorScheme = lightColorScheme(
    primary = Color(0xFF006C6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F4F2),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF51605D),
    tertiary = WarningColor,
    surface = Color(0xFFFEFFFD),
    surfaceVariant = Color(0xFFE3EAE7),
    background = Color(0xFFF7FAF8),
    error = DestructiveColor,
)

private val RsyncDarkColorScheme = darkColorScheme(
    primary = Color(0xFF82D5D2),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF9EF2EF),
    secondary = Color(0xFFB7C9C4),
    tertiary = Color(0xFFE7BF6B),
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF3F4946),
    background = Color(0xFF0B0F0E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun RsyncBackupTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colorScheme = if (darkTheme) RsyncDarkColorScheme else RsyncLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
