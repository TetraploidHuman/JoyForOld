package com.tetraploid.joyforold.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material3 字阶整体放大，默认跟随长辈可读性约定。 */
val Typography = Typography(
    displayLarge = JoyTextStyles.Display,
    displayMedium = JoyTextStyles.Title,
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = JoyTextSizes.TitleCompact,
        lineHeight = JoyTextSizes.TitleLineHeight,
        letterSpacing = 0.1.sp,
    ),
    headlineLarge = JoyTextStyles.Title,
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = JoyTextSizes.TitleCompact,
        lineHeight = JoyTextSizes.TitleLineHeight,
    ),
    headlineSmall = JoyTextStyles.Label,
    titleLarge = JoyTextStyles.Title,
    titleMedium = JoyTextStyles.Label,
    titleSmall = JoyTextStyles.Caption,
    bodyLarge = JoyTextStyles.Body,
    bodyMedium = JoyTextStyles.BodySecondary,
    bodySmall = JoyTextStyles.Caption,
    labelLarge = JoyTextStyles.Label,
    labelMedium = JoyTextStyles.Caption,
    labelSmall = JoyTextStyles.Hint,
)
