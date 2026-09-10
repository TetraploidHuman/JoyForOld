package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun ApiConfigPage(
    uiState: AgentUiState,
    onBack: () -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onUpdateAsrApiKey: (String) -> Unit,
    onUpdateAsrAppId: (String) -> Unit,
    onUpdateAsrAccessToken: (String) -> Unit,
    onUpdateAsrResourceId: (String) -> Unit,
    onSaveAsrConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = CortanaColors.OnBackground,
        unfocusedTextColor = CortanaColors.OnBackground,
        focusedBorderColor = CortanaColors.Accent,
        unfocusedBorderColor = CortanaColors.Divider,
        focusedLabelColor = CortanaColors.AccentMuted,
        unfocusedLabelColor = CortanaColors.OnBackgroundMuted,
        cursorColor = CortanaColors.Accent,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CortanaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "接口配置",
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.Title,
            )
            TextButton(onClick = onBack) {
                Text("返回", color = CortanaColors.AccentMuted, fontSize = JoyTextSizes.Label)
            }
        }

        Text(
            text = "填写后点保存。也可把 local.properties.example 复制为 local.properties 配置。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )

        Text(
            text = "大模型（火山方舟 LLM）",
            color = CortanaColors.Accent,
            fontSize = JoyTextSizes.Label,
        )
        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = onUpdateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("LLM API Key") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedButton(onClick = onSaveApiKey, modifier = Modifier.fillMaxWidth()) {
            Text("保存 LLM 配置")
        }

        Text(
            text = "语音识别（豆包 ASR）",
            color = CortanaColors.Accent,
            fontSize = JoyTextSizes.Label,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "新版填 API Key；旧版填 App ID + Access Token（二选一）。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )
        OutlinedTextField(
            value = uiState.asrApiKey,
            onValueChange = onUpdateAsrApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ASR API Key（新版）") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.asrAppId,
            onValueChange = onUpdateAsrAppId,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ASR App ID（旧版）") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.asrAccessToken,
            onValueChange = onUpdateAsrAccessToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ASR Access Token（旧版）") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.asrResourceId,
            onValueChange = onUpdateAsrResourceId,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ASR Resource ID") },
            placeholder = { Text("例如 volc.bigasr.sauc.duration") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedButton(onClick = onSaveAsrConfig, modifier = Modifier.fillMaxWidth()) {
            Text("保存语音识别配置")
        }
    }
}
