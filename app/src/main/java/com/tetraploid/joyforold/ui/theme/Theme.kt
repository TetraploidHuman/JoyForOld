package com.tetraploid.joyforold.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun paletteColorScheme(palette: CortanaColorPalette, darkTheme: Boolean) =
    if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onBackground,
            primaryContainer = palette.surfaceElevated,
            onPrimaryContainer = palette.onBackground,
            secondary = palette.accentMuted,
            onSecondary = palette.onBackground,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.surface,
            onSurface = palette.onBackground,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.onBackgroundSecondary,
            error = palette.error,
            onError = palette.onBackground,
            outline = palette.divider,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = ColorWhite,
            primaryContainer = palette.surfaceElevated,
            onPrimaryContainer = palette.onBackground,
            secondary = palette.accentMuted,
            onSecondary = ColorWhite,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.surface,
            onSurface = palette.onBackground,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.onBackgroundSecondary,
            error = palette.error,
            onError = ColorWhite,
            outline = palette.divider,
        )
    }

private val ColorWhite = androidx.compose.ui.graphics.Color(0xFFFFFFFF)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun JoyForOldTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) CortanaPalettes.Dark else CortanaPalettes.Light
    val colorScheme = paletteColorScheme(palette, darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalCortanaColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
