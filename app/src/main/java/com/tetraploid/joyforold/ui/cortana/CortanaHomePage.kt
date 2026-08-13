package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

private val CortanaBottomDockReservedHeight = 220.dp

private val fallbackSuggestions = listOf(
    "我要回家",
    "帮我读一下未读消息",
    "现在几点了",
    "今天天气怎么样",
    "打电话给女儿",
)

@Composable
fun CortanaHomePage(
    uiState: AgentUiState,
    onSuggestionClick: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onPauseAgent: () -> Unit,
    onResumeAgent: () -> Unit,
    onCancelAgent: () -> Unit,
    onClearConfirm: () -> Unit,
    onBinaryConfirm: () -> Unit,
    onBinaryCancel: () -> Unit,
    onDisambiguationSelect: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissUndo: () -> Unit = {},
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var selectedCategory by rememberSaveable { mutableStateOf<CortanaSearchCategory?>(null) }
    var expandedHints by rememberSaveable { mutableStateOf(false) }
    val suggestionChips = uiState.suggestionChips.ifEmpty { fallbackSuggestions }
    val primarySuggestion = suggestionChips.firstOrNull() ?: "给家人发消息"
    val dockSuggestions = suggestionChips.drop(1).ifEmpty { fallbackSuggestions.drop(1) }

    val greeting = when {
        uiState.isRunning -> "正在帮您处理，请稍候"
        uiState.isListening ||
            uiState.voiceInteractionState == VoiceInteractionState.Listening -> "我在听，请慢慢说"
        uiState.waitingForUserConfirm -> "请说出您的选择，或点下面的按钮"
        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt -> "请听我说"
        else -> "您好，需要我帮什么忙？"
    }

    val voiceListening = uiState.isListening ||
        uiState.voiceInteractionState == VoiceInteractionState.Listening
    val orbMood = when {
        uiState.isRunning -> CortanaOrbMood.Loading
        uiState.voiceInteractionState == VoiceInteractionState.Processing -> CortanaOrbMood.Processing
        voiceListening -> CortanaOrbMood.Listening
        uiState.waitingForUserConfirm -> CortanaOrbMood.Confirm
        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt -> CortanaOrbMood.Speaking
        else -> CortanaOrbMood.Idle
    }
    val hasContentPanel = uiState.isRunning ||
        voiceListening ||
        uiState.waitingForUserConfirm ||
        uiState.conversationCards.isNotEmpty() ||
        uiState.speechText.isNotBlank() ||
        uiState.voiceInteractionState != VoiceInteractionState.Idle

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CortanaColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = CortanaBottomDockReservedHeight),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    CortanaHeroHeader(
                        greeting = greeting,
                        orbMood = orbMood,
                        compact = hasContentPanel,
                        playEntryAnimation = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!uiState.accessibilityEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "还差一步：请到「设置」页打开无障碍服务。\n如果刚才强制停止过本应用，需要重新打开一次。",
                            color = CortanaColors.Error,
                            fontSize = JoyTextSizes.Caption,
                            lineHeight = JoyTextSizes.CaptionLineHeight,
                        )
                    } else if (!uiState.accessibilityServiceConnected) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "无障碍已打开，正在连接，请稍候…",
                            color = CortanaColors.OnBackgroundSecondary,
                            fontSize = JoyTextSizes.Caption,
                            lineHeight = JoyTextSizes.CaptionLineHeight,
                        )
                    }
                }

                if (uiState.conversationCards.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(if (hasContentPanel) 20.dp else 12.dp))
                    ConversationCardList(
                        cards = uiState.conversationCards,
                        isListening = uiState.isListening,
                        speechText = uiState.speechText,
                        onBinaryConfirm = onBinaryConfirm,
                        onBinaryCancel = onBinaryCancel,
                        onDismissConfirm = onClearConfirm,
                        onDisambiguationSelect = onDisambiguationSelect,
                        onUndo = onUndo,
                        onDismissUndo = onDismissUndo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.isRunning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                        ) {
                            RowActions(
                                isPaused = uiState.isPaused,
                                onPause = onPauseAgent,
                                onResume = onResumeAgent,
                                onCancel = onCancelAgent,
                            )
                        }
                    }
                } else if (!uiState.isRunning && !hasContentPanel) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Spacer(modifier = Modifier.height(28.dp))
                        SuggestionQuote(
                            text = primarySuggestion,
                            onClick = { onSuggestionClick(primarySuggestion) },
                        )
                        if (!expandedHints) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "查看更多常用说法",
                                color = CortanaColors.OnBackgroundSecondary,
                                fontSize = JoyTextSizes.BodySecondary,
                                lineHeight = JoyTextSizes.BodyLineHeight,
                                modifier = Modifier
                                    .clickable { expandedHints = true }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }

                if (uiState.speechText.isNotBlank() && !uiState.waitingForUserConfirm) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "您说的是：「${uiState.speechText}」",
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = JoyTextSizes.BodySecondary,
                        lineHeight = JoyTextSizes.BodyLineHeight,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        CortanaBottomDock(
            command = uiState.command,
            onCommandChange = onCommandChange,
            onMicClick = onMicClick,
            onCategoryClick = { category ->
                selectedCategory = category
                val prefix = category.prefix
                if (!uiState.command.startsWith(prefix)) {
                    onCommandChange(prefix)
                }
            },
            selectedCategory = selectedCategory,
            isListening = uiState.isListening,
            enabled = !uiState.isRunning,
            isRunning = uiState.isRunning,
            voiceBusy = uiState.voiceInteractionState != VoiceInteractionState.Idle &&
                !uiState.isListening,
            expandedHints = expandedHints && !uiState.isRunning && !hasContentPanel,
            onExpandHints = { expandedHints = !expandedHints },
            extraSuggestions = dockSuggestions,
            onSuggestionClick = onSuggestionClick,
            onSendClick = onSendClick,
            onCancelClick = onCancelClick,
            canExecute = uiState.accessibilityServiceConnected,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )
    }
}

@Composable
private fun SuggestionQuote(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Email,
            contentDescription = null,
            tint = CortanaColors.OnBackground,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "试试说：「$text」",
            color = CortanaColors.OnBackground,
            fontSize = JoyTextSizes.Body,
            lineHeight = JoyTextSizes.BodyLineHeight,
        )
    }
}

@Composable
private fun RowActions(
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { if (isPaused) onResume() else onPause() }) {
            Text(
                if (isPaused) "继续" else "暂停",
                color = CortanaColors.AccentMuted,
                fontSize = JoyTextSizes.Label,
            )
        }
        TextButton(onClick = onCancel) {
            Text("停止", color = CortanaColors.Error, fontSize = JoyTextSizes.Label)
        }
    }
}
