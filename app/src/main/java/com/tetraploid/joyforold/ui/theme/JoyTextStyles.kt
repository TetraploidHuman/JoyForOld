package com.tetraploid.joyforold.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 面向长辈的字号与行高约定：正文偏大、行距宽松、最小字号不低于 15sp。
 */
object JoyTextSizes {
    /** 品牌 / 大标题 */
    val Display = 26.sp
    /** 页面标题、问候语 */
    val Title = 22.sp
    /** 紧凑态问候、导航选中 */
    val TitleCompact = 20.sp
    /** 正文、对话内容、输入框 */
    val Body = 18.sp
    /** 次要正文、列表项 */
    val BodySecondary = 17.sp
    /** 按钮、标签、分区标题 */
    val Label = 17.sp
    /** 说明、提示、状态行 */
    val Caption = 16.sp
    /** 辅助说明（仍保持可读） */
    val Hint = 15.sp

    val BodyLineHeight = 28.sp
    val CaptionLineHeight = 24.sp
    val TitleLineHeight = 30.sp
}

object JoyTextStyles {
    val Display = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = JoyTextSizes.Display,
        lineHeight = 34.sp,
        letterSpacing = 0.2.sp,
    )
    val Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = JoyTextSizes.Title,
        lineHeight = JoyTextSizes.TitleLineHeight,
        letterSpacing = 0.15.sp,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = JoyTextSizes.Body,
        lineHeight = JoyTextSizes.BodyLineHeight,
        letterSpacing = 0.2.sp,
    )
    val BodySecondary = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = JoyTextSizes.BodySecondary,
        lineHeight = JoyTextSizes.BodyLineHeight,
        letterSpacing = 0.15.sp,
    )
    val Label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = JoyTextSizes.Label,
        lineHeight = JoyTextSizes.CaptionLineHeight,
        letterSpacing = 0.2.sp,
    )
    val Caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = JoyTextSizes.Caption,
        lineHeight = JoyTextSizes.CaptionLineHeight,
        letterSpacing = 0.15.sp,
    )
    val Hint = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = JoyTextSizes.Hint,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    )
}
