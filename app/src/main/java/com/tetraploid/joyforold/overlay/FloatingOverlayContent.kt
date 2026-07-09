package com.tetraploid.joyforold.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.ui.VoiceConfirmBanner

@Composable
fun FloatingOverlayContent(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    onRun: () -> Unit,
    onPreview: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoiceAndRun: () -> Unit,
    onStopVoiceOnly: () -> Unit,
) {
    val uiState by AgentRuntime.state.collectAsStateWithLifecycle()

    // 等待语音确认时：即使收起悬浮窗，也显示确认条（避免只看到小圆钮）
    if (!expanded) {
        if (uiState.waitingForUserConfirm && uiState.confirmPrompt != null) {
            Card(
                modifier = Modifier
                    .width(300.dp)
                    .padding(4.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF2FFFFFF)),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VoiceConfirmBanner(
                        prompt = uiState.confirmPrompt.orEmpty(),
                        isListening = uiState.isListening,
                        speechText = uiState.speechText,
                        onCancel = { AgentRuntime.clearPendingConfirmUI() },
                    )
                    TextButton(onClick = onToggleExpand) {
                        Text("展开助手面板")
                    }
                }
            }
        } else {
            FloatingActionButton(
                onClick = onToggleExpand,
                modifier = Modifier.padding(4.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Text("助手", color = Color.White)
            }
        }
        return
    }

    Card(
        modifier = Modifier
            .width(320.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF2FFFFFF)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("JoyForOld 助手", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onToggleExpand) { Text("收起") }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }

            Text(
                text = if (uiState.accessibilityEnabled) "可操作前台应用" else "请先开启无障碍",
                color = if (uiState.accessibilityEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (uiState.waitingForUserConfirm && uiState.confirmPrompt != null) {
                VoiceConfirmBanner(
                    prompt = uiState.confirmPrompt.orEmpty(),
                    isListening = uiState.isListening,
                    speechText = uiState.speechText,
                    onCancel = { AgentRuntime.clearPendingConfirmUI() },
                )
            }

            OutlinedTextField(
                value = uiState.command,
                onValueChange = AgentRuntime::updateCommand,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入指令") },
                placeholder = { Text("例如：点击搜索 / 返回") },
                singleLine = false,
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRun,
                    enabled = !uiState.isRunning && uiState.accessibilityEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isRunning) "执行中" else "执行")
                }
                OutlinedButton(
                    onClick = onPreview,
                    enabled = uiState.accessibilityEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("读页面")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (uiState.isListening) {
                            if (uiState.accessibilityEnabled) onStopVoiceAndRun() else onStopVoiceOnly()
                        } else {
                            onStartVoice()
                        }
                    },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isListening) "结束并执行" else "语音输入")
                }
                OutlinedButton(
                    onClick = { AgentRuntime.updateCommand(uiState.speechText) },
                    enabled = uiState.speechText.isNotBlank() && !uiState.isListening,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("填入文本")
                }
            }

            if (uiState.speechText.isNotBlank()) {
                Text(
                    text = "识别：${uiState.speechText}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(Color(0x11000000), RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (uiState.logs.isEmpty()) {
                    Text("切换到任意 App 后输入指令", style = MaterialTheme.typography.bodySmall)
                } else {
                    uiState.logs.takeLast(12).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
