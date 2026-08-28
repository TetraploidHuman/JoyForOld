package com.tetraploid.joyforold.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.ConversationCard
import com.tetraploid.joyforold.agent.ConversationCardFactory
import com.tetraploid.joyforold.agent.ConversationCardKind
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.ui.cortana.ConversationCardList
import com.tetraploid.joyforold.ui.cortana.CortanaHeroHeader
import com.tetraploid.joyforold.ui.cortana.CortanaOrbMood
import com.tetraploid.joyforold.ui.cortana.CortanaSearchBar
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes
import kotlinx.coroutines.delay

/** 悬浮窗上方不展示「计划」「执行中」卡片，只保留确认等交互卡。 */
private fun List<ConversationCard>.withoutPlanAndProgress(): List<ConversationCard> =
    filter { it.kind != ConversationCardKind.Plan && it.kind != ConversationCardKind.Progress }

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

    // 等待确认时必须画确认卡；视觉闩锁只在执行中藏卡，不能挡住确认
    val overlayCards = when {
        uiState.waitingForUserConfirm -> {
            uiState.overlaySessionCards.ifEmpty {
                ConversationCardFactory.overlaySessionCards(
                    uiState,
                    uiState.conversationCards,
                )
            }
        }
        uiState.visionAgentActive -> emptyList()
        uiState.overlaySessionCards.isNotEmpty() -> uiState.overlaySessionCards
        else -> ConversationCardFactory.overlaySessionCards(
            uiState,
            uiState.conversationCards,
        )
    }.withoutPlanAndProgress()

    val interactionText = when {
        uiState.isRunning && uiState.statusMessage.isNotBlank() ->
            uiState.statusMessage
        uiState.isListening && uiState.speechText.isNotBlank() ->
            uiState.speechText
        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt ->
            "请听我说"
        uiState.isListening ->
            "我在听，请慢慢说"
        uiState.voiceInteractionState == VoiceInteractionState.Processing ->
            "正在处理，请稍候"
        uiState.isRunning ->
            "正在帮您处理，请稍候"
        else ->
            "您好，需要我帮什么忙？"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CortanaColors.OverlayBackground),
    ) {
        // 仅确认/消歧等交互卡；计划与「执行中」不在悬浮窗展示
        if (overlayCards.isNotEmpty()) {
            ConversationCardList(
                cards = overlayCards,
                isListening = uiState.isListening,
                speechText = uiState.speechText,
                onBinaryConfirm = { agentRuntime.submitBinaryConfirm(approved = true) },
                onBinaryCancel = { agentRuntime.submitBinaryConfirm(approved = false) },
                onDismissConfirm = { agentRuntime.clearPendingConfirmUI() },
                onDisambiguationSelect = agentRuntime::selectDisambiguationOption,
                onUndo = agentRuntime::undoLastLocalAction,
                onDismissUndo = agentRuntime::dismissUndoOffer,
                cardSpacing = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (overlayCards.isNotEmpty()) 0.dp else 16.dp)
                .padding(bottom = if (overlayCards.isNotEmpty()) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CortanaHeroHeader(
                    greeting = interactionText,
                    orbMood = when {
                        uiState.isRunning -> CortanaOrbMood.Loading
                        uiState.voiceInteractionState == VoiceInteractionState.Processing ->
                            CortanaOrbMood.Processing
                        uiState.isListening ||
                            uiState.voiceInteractionState == VoiceInteractionState.Listening ->
                            CortanaOrbMood.Listening
                        uiState.waitingForUserConfirm -> CortanaOrbMood.Confirm
                        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt ->
                            CortanaOrbMood.Speaking
                        else -> CortanaOrbMood.Idle
                    },
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
                        fontSize = JoyTextSizes.BodySecondary,
                        lineHeight = JoyTextSizes.BodyLineHeight,
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
                voiceBusy = uiState.voiceInteractionState != VoiceInteractionState.Idle &&
                    !uiState.isListening,
                enabled = !uiState.isRunning,
                canExecute = uiState.accessibilityServiceConnected,
        )
    }
}
