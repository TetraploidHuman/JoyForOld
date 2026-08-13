package com.tetraploid.joyforold.ui.cortana

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes
import kotlin.math.roundToInt

@Composable
fun AssistRemoteScreenPage(
    uiState: AgentUiState,
    onBack: () -> Unit,
    onSendAssistTap: (Int, Int) -> Unit,
    onSendAssistSwipe: (Int, Int, Int, Int) -> Unit,
    onSendAssistAction: (String) -> Unit,
    onEndAssist: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CortanaColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CortanaColors.Surface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = CortanaColors.OnBackground)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "远程协助",
                    color = CortanaColors.Accent,
                    fontSize = JoyTextSizes.BodySecondary,
                )
                Text(
                    text = uiState.assistPeerDisplayName.ifBlank { "老人手机" },
                    color = CortanaColors.OnBackgroundMuted,
                    fontSize = JoyTextSizes.Caption,
                )
            }
            TextButton(onClick = onEndAssist) {
                Text("结束", color = CortanaColors.AccentMuted, fontSize = JoyTextSizes.Caption)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AssistScreenViewer(
                frameBytes = uiState.assistLatestFrameBytes,
                onTapNormalized = onSendAssistTap,
                onSwipeNormalized = onSendAssistSwipe,
                modifier = Modifier.fillMaxSize(),
                fullscreen = true,
            )
            AssistStreamStatsOverlay(
                fps = uiState.assistStreamFps,
                latencyMs = uiState.assistStreamLatencyMs,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CortanaColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onSendAssistAction("scroll_up") }, modifier = Modifier.weight(1f)) {
                Text("上滑")
            }
            OutlinedButton(onClick = { onSendAssistAction("scroll_down") }, modifier = Modifier.weight(1f)) {
                Text("下滑")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CortanaColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onSendAssistAction("back") }, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
            OutlinedButton(onClick = { onSendAssistAction("home") }, modifier = Modifier.weight(1f)) {
                Text("桌面")
            }
        }

        if (uiState.assistStatusMessage.isNotBlank()) {
            Text(
                text = uiState.assistStatusMessage,
                color = CortanaColors.AccentMuted,
                fontSize = JoyTextSizes.Caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun AssistStreamStatsOverlay(
    fps: Float,
    latencyMs: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = formatAssistStreamStats(fps, latencyMs),
        color = Color.White,
        fontSize = JoyTextSizes.Hint,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

fun formatAssistStreamStats(fps: Float, latencyMs: Long): String {
    val fpsText = if (fps >= 0.5f) "${fps.roundToInt()} FPS" else "— FPS"
    val latencyText = if (latencyMs in 0..5_000) "${latencyMs}ms" else "—ms"
    return "$fpsText · $latencyText"
}
