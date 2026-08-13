package com.tetraploid.joyforold.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun VoiceConfirmBanner(
    prompt: String,
    isListening: Boolean,
    speechText: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(CortanaColors.SurfaceElevated)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = prompt,
            color = CortanaColors.OnBackground,
            fontSize = JoyTextSizes.Body,
        )
        Text(
            text = if (isListening) {
                "我在听您说话。说完后稍停一下，就会继续。"
            } else {
                "正在打开麦克风，请稍候…"
            },
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )
        if (speechText.isNotBlank()) {
            Text(
                text = "听到了：$speechText",
                color = CortanaColors.AccentMuted,
                fontSize = JoyTextSizes.Caption,
                lineHeight = JoyTextSizes.CaptionLineHeight,
            )
        }
        TextButton(onClick = onCancel) {
            Text(
                "先取消",
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = JoyTextSizes.Label,
            )
        }
    }
}
