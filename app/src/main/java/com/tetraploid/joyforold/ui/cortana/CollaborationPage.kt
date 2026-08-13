package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.collaboration.AssistSessionPhase
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun CollaborationPage(
    uiState: AgentUiState,
    onSetAssistRole: (AssistRole) -> Unit,
    onSetAssistDisplayName: (String) -> Unit,
    onSetAssistServerHttpUrl: (String) -> Unit,
    onSetAssistServerWsUrl: (String) -> Unit,
    onStartElderAssist: () -> Unit,
    onJoinAssist: (String) -> Unit,
    onConnectBinding: (BindingDto) -> Unit,
    onDeleteBinding: (String) -> Unit,
    onOpenRemoteScreen: () -> Unit,
    onSendAssistAction: (String) -> Unit,
    onSendAssistTypeText: (String) -> Unit,
    onSendAssistCommand: (String) -> Unit,
    onEndAssist: () -> Unit,
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
    var pairCodeInput by remember(uiState.assistPhase) { mutableStateOf("") }
    var typeTextInput by remember { mutableStateOf("") }
    var commandInput by remember { mutableStateOf("") }
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
        if (uiState.assistMode) {
            AssistModeBanner(
                role = uiState.assistRole,
                phase = uiState.assistPhase,
                peerName = uiState.assistPeerDisplayName,
                pairCode = uiState.assistPairCode,
            )
        }

        Text(
            text = "家人远程协助",
            color = CortanaColors.Accent,
            fontSize = JoyTextSizes.Caption,
            letterSpacing = 1.sp,
        )
        Text(
            text = when (uiState.assistPhase) {
                AssistSessionPhase.ACTIVE ->
                    "协助进行中 · ${uiState.assistPeerDisplayName.ifBlank { "已连接" }}"
                AssistSessionPhase.WAITING_PEER ->
                    "等待家人输入协助码…"
                AssistSessionPhase.ENDED ->
                    uiState.assistStatusMessage.ifBlank { "协助已结束" }
                AssistSessionPhase.IDLE ->
                    when (uiState.assistRole) {
                        AssistRole.ELDER ->
                            "发起协助后把协助码告诉家人；已绑定过的家人可直接连你。"
                        AssistRole.CAREGIVER ->
                            "输入协助码，或从绑定列表一键连接老人手机。"
                    }
            },
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.assistRole == AssistRole.ELDER,
                onClick = { onSetAssistRole(AssistRole.ELDER) },
                label = { Text("我是老人") },
            )
            FilterChip(
                selected = uiState.assistRole == AssistRole.CAREGIVER,
                onClick = { onSetAssistRole(AssistRole.CAREGIVER) },
                label = { Text("我是儿女") },
            )
        }

        OutlinedTextField(
            value = uiState.assistDisplayName,
            onValueChange = onSetAssistDisplayName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("本机显示名") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.assistServerHttpUrl,
            onValueChange = onSetAssistServerHttpUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("协助服务器 HTTP") },
            placeholder = { Text("http://192.168.1.47:8787") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.assistServerWsUrl,
            onValueChange = onSetAssistServerWsUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("协助服务器 WebSocket") },
            placeholder = { Text("ws://192.168.1.47:8787/ws") },
            singleLine = true,
            colors = fieldColors,
        )

        if (uiState.assistStatusMessage.isNotBlank()) {
            Text(
                text = uiState.assistStatusMessage,
                color = CortanaColors.AccentMuted,
                fontSize = JoyTextSizes.Caption,
            )
        }

        when (uiState.assistRole) {
            AssistRole.ELDER -> ElderAssistSection(
                uiState = uiState,
                onStartElderAssist = onStartElderAssist,
                onEndAssist = onEndAssist,
            )
            AssistRole.CAREGIVER -> CaregiverAssistSection(
                uiState = uiState,
                pairCodeInput = pairCodeInput,
                onPairCodeChange = { pairCodeInput = it },
                typeTextInput = typeTextInput,
                onTypeTextChange = { typeTextInput = it },
                commandInput = commandInput,
                onCommandChange = { commandInput = it },
                onJoinAssist = onJoinAssist,
                onConnectBinding = onConnectBinding,
                onDeleteBinding = onDeleteBinding,
                onOpenRemoteScreen = onOpenRemoteScreen,
                onSendAssistAction = onSendAssistAction,
                onSendAssistTypeText = {
                    onSendAssistTypeText(it)
                    typeTextInput = ""
                },
                onSendAssistCommand = {
                    onSendAssistCommand(it)
                    commandInput = ""
                },
                onEndAssist = onEndAssist,
            )
        }

        Text(
            text = "家人联系人 / 预设指令",
            color = CortanaColors.Accent,
            fontSize = JoyTextSizes.Caption,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp),
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
        TextButton(onClick = onSave) {
            Text("保存家人协助与预设", color = CortanaColors.Accent)
        }
    }
}

@Composable
private fun ElderAssistSection(
    uiState: AgentUiState,
    onStartElderAssist: () -> Unit,
    onEndAssist: () -> Unit,
) {
    if (uiState.assistPhase == AssistSessionPhase.IDLE || uiState.assistPhase == AssistSessionPhase.ENDED) {
        Button(onClick = onStartElderAssist, modifier = Modifier.fillMaxWidth()) {
            Text("请家人帮忙")
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.assistPairCode.isNotBlank()) {
                Text(
                    text = uiState.assistPairCode.chunked(1).joinToString(" "),
                    color = CortanaColors.OnBackground,
                    fontSize = 40.sp,
                )
            } else if (uiState.assistPhase == AssistSessionPhase.ACTIVE) {
                Text(
                    text = "家人已连接",
                    color = CortanaColors.OnBackground,
                    fontSize = JoyTextSizes.Body,
                )
            }
            OutlinedButton(onClick = onEndAssist, modifier = Modifier.fillMaxWidth()) {
                Text("结束协助")
            }
        }
    }
}

@Composable
private fun CaregiverAssistSection(
    uiState: AgentUiState,
    pairCodeInput: String,
    onPairCodeChange: (String) -> Unit,
    typeTextInput: String,
    onTypeTextChange: (String) -> Unit,
    commandInput: String,
    onCommandChange: (String) -> Unit,
    onJoinAssist: (String) -> Unit,
    onConnectBinding: (BindingDto) -> Unit,
    onDeleteBinding: (String) -> Unit,
    onOpenRemoteScreen: () -> Unit,
    onSendAssistAction: (String) -> Unit,
    onSendAssistTypeText: (String) -> Unit,
    onSendAssistCommand: (String) -> Unit,
    onEndAssist: () -> Unit,
) {
    if (uiState.assistBindings.isNotEmpty()) {
        Text("已绑定家人", color = CortanaColors.OnBackgroundMuted, fontSize = JoyTextSizes.Caption)
        uiState.assistBindings.forEach { binding ->
            val label = binding.elderDisplayName.ifBlank { "老人" }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = CortanaColors.OnBackground)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onConnectBinding(binding) }) {
                        Text("连接", color = CortanaColors.Accent)
                    }
                    TextButton(onClick = { onDeleteBinding(binding.id) }) {
                        Text("删除", color = CortanaColors.OnBackgroundMuted)
                    }
                }
            }
        }
    }

    if (uiState.assistPhase != AssistSessionPhase.ACTIVE) {
        OutlinedTextField(
            value = pairCodeInput,
            onValueChange = onPairCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("协助码") },
            singleLine = true,
        )
        Button(
            onClick = { onJoinAssist(pairCodeInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = pairCodeInput.length == 6,
        ) {
            Text("输入协助码连接")
        }
    } else {
        Button(onClick = onOpenRemoteScreen, modifier = Modifier.fillMaxWidth()) {
            Text("进入远程画面（全屏）")
        }
        if (uiState.assistStreamFps >= 0.5f || uiState.assistStreamLatencyMs >= 0L) {
            Text(
                text = formatAssistStreamStats(uiState.assistStreamFps, uiState.assistStreamLatencyMs),
                color = CortanaColors.AccentMuted,
                fontSize = JoyTextSizes.Caption,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSendAssistAction("back") }, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
            OutlinedButton(onClick = { onSendAssistAction("home") }, modifier = Modifier.weight(1f)) {
                Text("桌面")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSendAssistAction("scroll_down") }, modifier = Modifier.weight(1f)) {
                Text("下滑")
            }
            OutlinedButton(onClick = { onSendAssistAction("scroll_up") }, modifier = Modifier.weight(1f)) {
                Text("上滑")
            }
        }
        OutlinedTextField(
            value = typeTextInput,
            onValueChange = onTypeTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入文字到老人手机") },
            singleLine = true,
        )
        Button(
            onClick = { onSendAssistTypeText(typeTextInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = typeTextInput.isNotBlank(),
        ) {
            Text("发送文字")
        }
        OutlinedTextField(
            value = commandInput,
            onValueChange = onCommandChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("发指令（在老人手机跑 Agent）") },
            singleLine = true,
        )
        Button(
            onClick = { onSendAssistCommand(commandInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = commandInput.isNotBlank(),
        ) {
            Text("执行指令")
        }
        OutlinedButton(onClick = onEndAssist, modifier = Modifier.fillMaxWidth()) {
            Text("结束协助")
        }
    }
}

@Composable
private fun AssistModeBanner(
    role: AssistRole,
    phase: AssistSessionPhase,
    peerName: String,
    pairCode: String,
) {
    val label = when (phase) {
        AssistSessionPhase.WAITING_PEER ->
            if (pairCode.isNotBlank()) "协助已开启 · 协助码 $pairCode" else "等待家人连接…"
        AssistSessionPhase.ACTIVE -> {
            val peer = peerName.ifBlank { if (role == AssistRole.ELDER) "家人" else "老人" }
            if (role == AssistRole.ELDER) "家人正在协助 · $peer" else "正在远程协助 · $peer"
        }
        AssistSessionPhase.ENDED -> "协助已结束"
        AssistSessionPhase.IDLE -> "协助模式"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CortanaColors.Accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CortanaColors.Accent, fontSize = JoyTextSizes.BodySecondary)
    }
}
