package com.tetraploid.joyforold.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CortanaColorScheme = darkColorScheme(
    primary = CortanaColors.Accent,
    onPrimary = CortanaColors.OnBackground,
    primaryContainer = CortanaColors.SurfaceElevated,
    onPrimaryContainer = CortanaColors.OnBackground,
    secondary = CortanaColors.AccentMuted,
    onSecondary = CortanaColors.OnBackground,
    background = CortanaColors.Background,
    onBackground = CortanaColors.OnBackground,
    surface = CortanaColors.Surface,
    onSurface = CortanaColors.OnBackground,
    surfaceVariant = CortanaColors.SurfaceElevated,
    onSurfaceVariant = CortanaColors.OnBackgroundSecondary,
    error = CortanaColors.Error,
    onError = CortanaColors.OnBackground,
    outline = CortanaColors.Divider,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun JoyForOldTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = CortanaColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = CortanaColors.Background.toArgb()
            window.navigationBarColor = CortanaColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
