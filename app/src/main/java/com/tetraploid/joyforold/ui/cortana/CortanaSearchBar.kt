package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

internal enum class InputActionMode {
    Mic,
    Send,
    Cancel,
}

/**
 * 底部输入框右侧动作：
 * - 空闲且无文字 → 麦克风
 * - 有文字 → 发送（聆听中已识别出文字时可点发送以结束并执行）
 * - 执行中 / 语音忙且尚无文字 → 叉叉取消
 */
internal fun resolveInputActionMode(
    value: String,
    isListening: Boolean,
    isRunning: Boolean,
    voiceBusy: Boolean = false,
): InputActionMode = when {
    isRunning -> InputActionMode.Cancel
    (isListening || voiceBusy) && value.isBlank() -> InputActionMode.Cancel
    value.isNotBlank() -> InputActionMode.Send
    else -> InputActionMode.Mic
}

@Composable
fun CortanaSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "点这里输入，或按麦克风说话",
    isListening: Boolean = false,
    isRunning: Boolean = false,
    /** 播报提示 / 识别处理中等：尚未开麦或刚结束聆听，应显示取消而非麦克风 */
    voiceBusy: Boolean = false,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") canExecute: Boolean = false,
) {
    val actionMode = resolveInputActionMode(
        value = value,
        isListening = isListening,
        isRunning = isRunning,
        voiceBusy = voiceBusy,
    )

    val actionEnabled = when (actionMode) {
        InputActionMode.Mic -> enabled
        InputActionMode.Send -> value.isNotBlank() && (enabled || isListening)
        InputActionMode.Cancel -> true
    }

    val actionIcon: ImageVector = when (actionMode) {
        InputActionMode.Mic -> Icons.Outlined.Mic
        InputActionMode.Send -> Icons.AutoMirrored.Outlined.Send
        InputActionMode.Cancel -> Icons.Outlined.Close
    }

    val actionDescription = when (actionMode) {
        InputActionMode.Mic -> "开始录音"
        InputActionMode.Send -> if (isListening) "结束并发送" else "发送"
        InputActionMode.Cancel -> when {
            isRunning -> "取消任务"
            isListening || voiceBusy -> "取消语音"
            else -> "取消"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(CortanaColors.SearchBarBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            enabled = enabled && !isListening && !isRunning && !voiceBusy,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = CortanaColors.SearchBarText,
                fontSize = JoyTextSizes.Body,
                lineHeight = JoyTextSizes.BodyLineHeight,
            ),
            cursorBrush = SolidColor(CortanaColors.Accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = CortanaColors.OnBackgroundMuted,
                            fontSize = JoyTextSizes.Body,
                            lineHeight = JoyTextSizes.BodyLineHeight,
                        )
                    }
                    inner()
                }
            },
        )

        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .background(
                    if (actionEnabled) CortanaColors.Accent else CortanaColors.Accent.copy(alpha = 0.45f),
                )
                .clickable(enabled = actionEnabled) {
                    when (actionMode) {
                        InputActionMode.Mic -> onMicClick()
                        InputActionMode.Send -> onSendClick()
                        InputActionMode.Cancel -> onCancelClick()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = actionIcon,
                contentDescription = actionDescription,
                // 动作键始终蓝底，固定用白图标，避免随主题 OnBackground 反色后「看起来没切换」
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
