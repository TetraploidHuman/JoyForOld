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
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.ui.theme.CortanaColors

private val CortanaBottomDockReservedHeight = 180.dp

private val primarySuggestion = "发送消息给家人"
private val extraSuggestions = listOf(
    "我要回家",
    "帮我读一下未读消息",
    "几点了",
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
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var selectedCategory by rememberSaveable { mutableStateOf<CortanaSearchCategory?>(null) }
    var expandedHints by rememberSaveable { mutableStateOf(false) }

    val greeting = when {
        uiState.isRunning -> "正在为您处理…"
        uiState.isListening -> "我在听，请说…"
        uiState.waitingForUserConfirm -> "需要您确认一下"
        uiState.voiceInteractionState == VoiceInteractionState.SpeakingPrompt -> "请听我说…"
        else -> "嗨，在想什么呢？"
    }

    val orbActive = uiState.isRunning || uiState.isListening || uiState.waitingForUserConfirm
    val hasContentPanel = uiState.isRunning ||
        uiState.isListening ||
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
                        orbActive = orbActive,
                        compact = hasContentPanel,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!uiState.accessibilityEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "请先在「设置」页开启无障碍服务（若曾强制停止本应用，需重新开启）",
                            color = CortanaColors.Error,
                            fontSize = 13.sp,
                        )
                    } else if (!uiState.accessibilityServiceConnected) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "无障碍已开启，正在连接服务…",
                            color = CortanaColors.OnBackgroundSecondary,
                            fontSize = 13.sp,
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
                                text = "查看所有提示",
                                color = CortanaColors.OnBackgroundSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { expandedHints = true }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }

                if (uiState.speechText.isNotBlank() && !uiState.waitingForUserConfirm) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "「${uiState.speechText}」",
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = 14.sp,
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
            expandedHints = expandedHints && !uiState.isRunning && !hasContentPanel,
            onExpandHints = { expandedHints = !expandedHints },
            extraSuggestions = extraSuggestions,
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Email,
            contentDescription = null,
            tint = CortanaColors.OnBackground,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "「$text」",
            color = CortanaColors.OnBackground,
            fontSize = 15.sp,
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
            Text(if (isPaused) "继续" else "暂停", color = CortanaColors.AccentMuted)
        }
        TextButton(onClick = onCancel) {
            Text("停止", color = CortanaColors.Error)
        }
    }
}
