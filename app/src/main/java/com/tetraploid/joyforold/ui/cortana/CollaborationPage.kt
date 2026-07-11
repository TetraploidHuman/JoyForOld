package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ui.theme.CortanaColors

@Composable
fun CollaborationPage(
    uiState: AgentUiState,
    onUpdateDaughterPhone: (String) -> Unit,
    onUpdateSonPhone: (String) -> Unit,
    onUpdateEmergencyPhone: (String) -> Unit,
    onUpdateEmergencyMessage: (String) -> Unit,
    onUpdateHomeAddress: (String) -> Unit,
    onUpdatePresetPhraseGoHome: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
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
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "家人协助与预设指令",
            color = CortanaColors.Accent,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
        )
        Text(
            text = "配置家人手机号、紧急求助话术、家的地址，以及「我要回家」等固定口令。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 13.sp,
        )

        OutlinedTextField(
            value = uiState.daughterPhone,
            onValueChange = onUpdateDaughterPhone,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("女儿手机号") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.sonPhone,
            onValueChange = onUpdateSonPhone,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("儿子手机号") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.emergencyPhone,
            onValueChange = onUpdateEmergencyPhone,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("紧急联系人手机号") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.emergencyMessage,
            onValueChange = onUpdateEmergencyMessage,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("紧急求助短信内容") },
            minLines = 2,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.homeAddress,
            onValueChange = onUpdateHomeAddress,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("家的地址") },
            minLines = 2,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.presetPhraseGoHome,
            onValueChange = onUpdatePresetPhraseGoHome,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("回家预设说法（可多条，逗号分隔）") },
            minLines = 2,
            colors = fieldColors,
        )
        Text(
            text = "命中后会直接走地图导航深链，不依赖页面点击。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 12.sp,
        )
        TextButton(onClick = onSave) {
            Text("保存家人协助与预设", color = CortanaColors.Accent)
        }
    }
}
