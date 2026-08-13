package com.tetraploid.joyforold.ui.components.w10m

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

/**
 * Windows 10 Mobile design tokens: flat surfaces, sharp corners, accent underline focus.
 * 字号与触控高度按长辈可读性放大。
 */
object W10mTokens {
    val CornerRadius: Dp = 0.dp

    val ButtonMinHeight: Dp = 56.dp
    val ButtonHorizontalPadding: Dp = 22.dp
    val ButtonVerticalPadding: Dp = 14.dp
    val ButtonBorderWidth: Dp = 2.dp

    val TextFieldMinHeight: Dp = 58.dp
    val TextFieldUnderlineIdle: Dp = 1.5.dp
    val TextFieldUnderlineFocused: Dp = 2.5.dp
    val TextFieldContentPaddingHorizontal: Dp = 6.dp
    val TextFieldContentPaddingVertical: Dp = 12.dp

    val ToggleTrackWidth: Dp = 52.dp
    val ToggleTrackHeight: Dp = 26.dp
    val ToggleThumbSize: Dp = 26.dp
    val ToggleThumbTravel: Dp = 26.dp

    val SectionSpacing: Dp = 14.dp
    val ChipHeight: Dp = 44.dp
    val ChipHorizontalPadding: Dp = 16.dp

    val TitleLetterSpacing = 0.8.sp
    val BodySize = JoyTextSizes.Body
    val CaptionSize = JoyTextSizes.Caption
    val SectionTitleSize = JoyTextSizes.Label
    val ButtonLabelSize = JoyTextSizes.Label
}
