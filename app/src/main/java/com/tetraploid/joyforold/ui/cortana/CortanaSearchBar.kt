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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.CortanaColors

private enum class InputActionMode {
    Mic,
    Send,
    Cancel,
}

@Composable
fun CortanaSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "在此输入或说话…",
    isListening: Boolean = false,
    isRunning: Boolean = false,
    enabled: Boolean = true,
    canExecute: Boolean = false,
) {
    val actionMode = when {
        isRunning -> InputActionMode.Cancel
        value.isNotBlank() -> InputActionMode.Send
        else -> InputActionMode.Mic
    }

    val actionEnabled = when (actionMode) {
        InputActionMode.Mic -> enabled
        InputActionMode.Send -> enabled && value.isNotBlank()
        InputActionMode.Cancel -> true
    }

    val actionIcon: ImageVector = when (actionMode) {
        InputActionMode.Mic -> Icons.Outlined.Mic
        InputActionMode.Send -> Icons.AutoMirrored.Outlined.ArrowBack
        InputActionMode.Cancel -> Icons.Outlined.Close
    }

    val actionDescription = when (actionMode) {
        InputActionMode.Mic -> if (isListening) "录音中" else "开始录音"
        InputActionMode.Send -> "发送"
        InputActionMode.Cancel -> "取消"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(CortanaColors.SearchBarBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            enabled = enabled && !isListening && !isRunning,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = CortanaColors.SearchBarText,
                fontSize = 16.sp,
            ),
            cursorBrush = SolidColor(CortanaColors.Accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = CortanaColors.OnBackgroundMuted,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                }
            },
        )

        Box(
            modifier = Modifier
                .width(48.dp)
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
                tint = CortanaColors.OnBackground,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
