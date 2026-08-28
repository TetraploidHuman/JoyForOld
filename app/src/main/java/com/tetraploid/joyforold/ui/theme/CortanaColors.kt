package com.tetraploid.joyforold.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class CortanaColorPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val accent: Color,
    val accentGlow: Color,
    val accentMuted: Color,
    val onBackground: Color,
    val onBackgroundSecondary: Color,
    val onBackgroundMuted: Color,
    val pillBackground: Color,
    val searchBarBackground: Color,
    val searchBarText: Color,
    val divider: Color,
    val success: Color,
    val error: Color,
    val overlayBackground: Color,
    val stepCompleted: Color,
    val stepActive: Color,
    val stepPending: Color,
)

object CortanaPalettes {
    val Dark = CortanaColorPalette(
        isDark = true,
        background = Color(0xFF000000),
        surface = Color(0xFF1F1F1F),
        surfaceElevated = Color(0xFF2D2D30),
        accent = Color(0xFF0078D7),
        accentGlow = Color(0xFF4CC2FF),
        accentMuted = Color(0xFF6BA4D8),
        onBackground = Color(0xFFFFFFFF),
        onBackgroundSecondary = Color(0xFFC8C8C8),
        onBackgroundMuted = Color(0xFFA0A0A0),
        pillBackground = Color(0x33FFFFFF),
        searchBarBackground = Color(0xFFF2F2F2),
        searchBarText = Color(0xFF1A1A1A),
        divider = Color(0xFF3F3F46),
        success = Color(0xFF6FCF97),
        error = Color(0xFFFF6B6B),
        overlayBackground = Color(0xFF1F1F1F),
        stepCompleted = Color(0xFF6B6B6B),
        stepActive = Color(0xFFE0E0E0),
        stepPending = Color(0xFF5A5A5A),
    )

    val Light = CortanaColorPalette(
        isDark = false,
        background = Color(0xFFF3F3F3),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFECECEC),
        accent = Color(0xFF0078D4),
        accentGlow = Color(0xFF50A8FF),
        accentMuted = Color(0xFF005A9E),
        onBackground = Color(0xFF121212),
        onBackgroundSecondary = Color(0xFF424242),
        onBackgroundMuted = Color(0xFF5A5A5A),
        pillBackground = Color(0x1A000000),
        searchBarBackground = Color(0xFFFFFFFF),
        searchBarText = Color(0xFF1A1A1A),
        divider = Color(0xFFD6D6D6),
        success = Color(0xFF2E8B57),
        error = Color(0xFFD13438),
        overlayBackground = Color(0xFFF3F3F3),
        stepCompleted = Color(0xFF9E9E9E),
        stepActive = Color(0xFF0078D4),
        stepPending = Color(0xFFC4C4C4),
    )
}

val LocalCortanaColors = staticCompositionLocalOf { CortanaPalettes.Light }

object CortanaColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.background

    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.surface

    val SurfaceElevated: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.surfaceElevated

    val Accent: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.accent

    val AccentGlow: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.accentGlow

    val AccentMuted: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.accentMuted

    val OnBackground: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.onBackground

    val OnBackgroundSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.onBackgroundSecondary

    val OnBackgroundMuted: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.onBackgroundMuted

    val PillBackground: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.pillBackground

    val SearchBarBackground: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.searchBarBackground

    val SearchBarText: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.searchBarText

    val Divider: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.divider

    val Success: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.success

    val Error: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.error

    val OverlayBackground: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.overlayBackground

    val StepCompleted: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.stepCompleted

    val StepActive: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.stepActive

    val StepPending: Color
        @Composable @ReadOnlyComposable get() = LocalCortanaColors.current.stepPending
}
