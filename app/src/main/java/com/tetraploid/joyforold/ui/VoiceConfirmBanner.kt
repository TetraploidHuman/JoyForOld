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
            fontSize = 16.sp,
        )
        Text(
            text = if (isListening) {
                "正在听您回答，说完停顿即可自动继续"
            } else {
                "正在准备麦克风..."
            },
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 13.sp,
        )
        if (speechText.isNotBlank()) {
            Text(
                text = "识别：$speechText",
                color = CortanaColors.AccentMuted,
                fontSize = 13.sp,
            )
        }
        TextButton(onClick = onCancel) {
            Text("取消本次确认", color = CortanaColors.OnBackgroundSecondary)
        }
    }
}
