package com.tetraploid.joyforold.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.ui.cortana.CortanaHeroHeader
import com.tetraploid.joyforold.ui.cortana.CortanaSearchBar
import com.tetraploid.joyforold.ui.cortana.OverlayInteractionCard
import com.tetraploid.joyforold.ui.theme.CortanaColors
import kotlinx.coroutines.delay

fun shouldShowOverlayDialog(
    isRunning: Boolean,
    isListening: Boolean,
    waitingForUserConfirm: Boolean,
    voiceInteractionState: VoiceInteractionState,
): Boolean {
    return isRunning ||
        isListening ||
        waitingForUserConfirm ||
        voiceInteractionState != VoiceInteractionState.Idle
}

@Composable
fun FloatingOverlayContent(
    agentRuntime: AgentRuntime,
    onRun: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoiceAndRun: () -> Unit,
    onStopVoiceOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    val uiState by agentRuntime.state.collectAsStateWithLifecycle()
    val visible = shouldShowOverlayDialog(
        isRunning = uiState.isRunning,
        isListening = uiState.isListening,
        waitingForUserConfirm = uiState.waitingForUserConfirm,
        voiceInteractionState = uiState.voiceInteractionState,
    )

    LaunchedEffect(Unit) {
        agentRuntime.refreshAccessibilityState()
        while (true) {
            delay(2_000)
            agentRuntime.refreshAccessibilityState()
        }
    }

    if (!visible) return

    val interactionText = when {
        uiState.isRunning && uiState.statusMessage.isNotBlank() ->
            uiState.statusMessage
        uiState.isListening && uiState.speechText.isNotBlank() ->
            uiState.speechText
        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt ->
            "请听我说…"
        uiState.isListening ->
            "我在听，请说…"
        uiState.voiceInteractionState == VoiceInteractionState.Processing ->
            "正在处理…"
        uiState.isRunning ->
            "正在为您处理…"
        else ->
            "有什么可以帮您？"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        uiState.overlayInteractionCard?.let { card ->
            OverlayInteractionCard(
                card = card,
                isListening = uiState.isListening,
                speechText = uiState.speechText,
                onBinaryConfirm = { agentRuntime.submitBinaryConfirm(approved = true) },
                onBinaryCancel = { agentRuntime.submitBinaryConfirm(approved = false) },
                onDismissConfirm = { agentRuntime.clearPendingConfirmUI() },
                onDisambiguationSelect = agentRuntime::selectDisambiguationOption,
                onUndo = agentRuntime::undoLastLocalAction,
                onDismissUndo = agentRuntime::dismissUndoOffer,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CortanaColors.OverlayBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CortanaHeroHeader(
                    greeting = interactionText,
                    orbActive = uiState.isRunning || uiState.isListening || uiState.waitingForUserConfirm,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (uiState.speechText.isNotBlank() &&
                    !uiState.waitingForUserConfirm &&
                    uiState.isListening
                ) {
                    Text(
                        text = "「${uiState.speechText}」",
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (uiState.isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                if (uiState.isPaused) agentRuntime.resumeAgent() else agentRuntime.pauseAgent()
                            },
                        ) {
                            Text(
                                if (uiState.isPaused) "继续" else "暂停",
                                color = CortanaColors.AccentMuted,
                            )
                        }
                    }
                }
            }

            CortanaSearchBar(
                value = uiState.command,
                onValueChange = agentRuntime::updateCommand,
                onMicClick = onStartVoice,
                onSendClick = {
                    if (uiState.isListening) {
                        if (uiState.accessibilityServiceConnected) onStopVoiceAndRun() else onStopVoiceOnly()
                    } else if (uiState.command.isNotBlank()) {
                        onRun()
                    }
                },
                onCancelClick = {
                    if (uiState.isRunning) {
                        agentRuntime.cancelAgent()
                    } else {
                        onCancel()
                    }
                },
                isListening = uiState.isListening,
                isRunning = uiState.isRunning,
                enabled = !uiState.isRunning,
                canExecute = uiState.accessibilityServiceConnected,
            )
        }
    }
}
