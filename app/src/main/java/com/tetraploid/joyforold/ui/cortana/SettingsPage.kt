package com.tetraploid.joyforold.ui.cortana

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission
import com.tetraploid.joyforold.system.NotificationAccessPermission
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset

@Composable
fun SettingsPage(
    uiState: AgentUiState,
    overlayRunning: Boolean,
    onRequestAudioPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onUpdateAsrApiKey: (String) -> Unit,
    onUpdateAsrAppId: (String) -> Unit,
    onUpdateAsrAccessToken: (String) -> Unit,
    onUpdateAsrResourceId: (String) -> Unit,
    onSaveAsrConfig: () -> Unit,
    onUpdateWakeWordPhrase: (String) -> Unit,
    onUpdateWakeWordScore: (String) -> Unit,
    onUpdateWakeWordThreshold: (String) -> Unit,
    onApplyWakeWordPreset: (WakeWordSensitivityPreset) -> Unit,
    onSetWakeWordEnabled: (Boolean) -> Unit,
    onSetWakeWordSileroVad: (Boolean) -> Unit,
    onSetWakeWordSecondStage: (Boolean) -> Unit,
    onSaveWakeWordConfig: () -> Unit,
    onTestWakeWord: () -> Unit,
    onStartCalibration: () -> Unit,
    onRecordCalibrationStep: () -> Unit,
    onUpdateCommand: (String) -> Unit,
    onRunAgent: () -> Unit,
    onPreviewPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("权限与服务")
        StatusLine("无障碍", uiState.accessibilityEnabled)
        if (uiState.accessibilityEnabled && !uiState.accessibilityServiceConnected) {
            Text(
                text = "已开启，服务连接中…",
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = "提示：在系统设置里「强制停止」本应用后，无障碍会被自动关闭，需重新开启。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        StatusLine("麦克风", uiState.recordAudioGranted)
        StatusLine("联系人", uiState.readContactsGranted)
        StatusLine("通知使用权", uiState.notificationAccessGranted)
        StatusLine("悬浮助手", overlayRunning)

        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开无障碍设置") }
        OutlinedButton(
            onClick = { context.startActivity(OverlayPermission.createSettingsIntent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开启悬浮窗权限") }
        OutlinedButton(
            onClick = onRequestAudioPermission,
            enabled = !uiState.recordAudioGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (uiState.recordAudioGranted) "麦克风已授予" else "授予麦克风权限") }
        OutlinedButton(
            onClick = onRequestContactsPermission,
            enabled = !uiState.readContactsGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (uiState.readContactsGranted) "联系人已授予" else "授予联系人权限") }
        OutlinedButton(
            onClick = { context.startActivity(NotificationAccessPermission.createSettingsIntent(context)) },
            enabled = !uiState.notificationAccessGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (uiState.notificationAccessGranted) "通知使用权已开启" else "开启通知使用权") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (OverlayPermission.canDrawOverlays(context)) {
                        FloatingOverlayService.start(context)
                        onToggleOverlay(true)
                    } else {
                        context.startActivity(OverlayPermission.createSettingsIntent(context))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("启动悬浮助手") }
            OutlinedButton(
                onClick = {
                    FloatingOverlayService.stop(context)
                    onToggleOverlay(false)
                },
                modifier = Modifier.weight(1f),
            ) { Text("关闭悬浮助手") }
        }

        SectionDivider()
        SectionTitle("API 配置")
        Text("模型：${uiState.modelName}", color = CortanaColors.OnBackgroundMuted, fontSize = 12.sp)
        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = onUpdateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("DeepSeek API Key") },
            singleLine = true,
            colors = fieldColors,
        )
        TextButton(onClick = onSaveApiKey) { Text("保存 DeepSeek", color = CortanaColors.Accent) }

        SectionDivider()
        SectionTitle("豆包语音识别")
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
            singleLine = true,
            colors = fieldColors,
        )
        TextButton(onClick = onSaveAsrConfig) { Text("保存语音识别配置", color = CortanaColors.Accent) }

        SectionDivider()
        SectionTitle("本地语音唤醒")
        Text(
            "模型：${uiState.wakeWordModelVersion}",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = uiState.wakeWordEnabled,
                onCheckedChange = onSetWakeWordEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CortanaColors.Accent,
                    checkedTrackColor = CortanaColors.SurfaceElevated,
                ),
            )
            Text(
                if (uiState.wakeWordRunning) "唤醒服务已运行" else "唤醒服务未运行",
                color = if (uiState.wakeWordRunning) CortanaColors.Success else CortanaColors.OnBackgroundMuted,
                fontSize = 13.sp,
            )
        }
        uiState.wakeWordTestHint?.let {
            Text(it, color = CortanaColors.AccentMuted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeWordSensitivityPreset.entries.forEach { preset ->
                val selected = uiState.wakeWordPreset == preset
                OutlinedButton(
                    onClick = { onApplyWakeWordPreset(preset) },
                    enabled = !selected,
                    modifier = Modifier.weight(1f),
                ) { Text(if (selected) "${preset.label}✓" else preset.label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = uiState.wakeWordSileroVadEnabled, onCheckedChange = onSetWakeWordSileroVad)
            Text("VAD", color = CortanaColors.OnBackgroundSecondary, fontSize = 13.sp)
            Switch(checked = uiState.wakeWordSecondStageEnabled, onCheckedChange = onSetWakeWordSecondStage)
            Text("二阶段", color = CortanaColors.OnBackgroundSecondary, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = uiState.wakeWordPhrase,
            onValueChange = onUpdateWakeWordPhrase,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("唤醒词") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.wakeWordKeywordScore.toString(),
            onValueChange = onUpdateWakeWordScore,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("命中分数") },
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = uiState.wakeWordKeywordThreshold.toString(),
            onValueChange = onUpdateWakeWordThreshold,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("命中阈值") },
            singleLine = true,
            colors = fieldColors,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSaveWakeWordConfig, modifier = Modifier.weight(1f)) {
                Text("保存唤醒词")
            }
            OutlinedButton(onClick = onTestWakeWord, modifier = Modifier.weight(1f)) {
                Text("测试")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStartCalibration,
                modifier = Modifier.weight(1f),
                enabled = !uiState.wakeWordCalibrationRunning,
            ) { Text("开始标定") }
            OutlinedButton(
                onClick = onRecordCalibrationStep,
                modifier = Modifier.weight(1f),
                enabled = uiState.wakeWordCalibrationRunning,
            ) {
                Text(
                    when (uiState.wakeWordCalibrationStep) {
                        0, 1, 2 -> "样本 ${uiState.wakeWordCalibrationStep + 1}/3"
                        3 -> "环境音"
                        else -> "完成"
                    },
                )
            }
        }
        uiState.wakeWordCalibrationHint?.let {
            Text(it, color = CortanaColors.AccentMuted, fontSize = 12.sp)
        }

        SectionDivider()
        SectionTitle("指令测试")
        OutlinedTextField(
            value = uiState.command,
            onValueChange = onUpdateCommand,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("测试指令") },
            colors = fieldColors,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRunAgent,
                enabled = !uiState.isRunning && uiState.accessibilityEnabled,
                modifier = Modifier.weight(1f),
            ) { Text("让 AI 执行") }
            OutlinedButton(
                onClick = onPreviewPage,
                enabled = uiState.accessibilityEnabled,
                modifier = Modifier.weight(1f),
            ) { Text("读取页面") }
        }

        SectionDivider()
        SectionTitle("运行日志")
        uiState.logs.takeLast(20).forEach { line ->
            Text(line, color = CortanaColors.OnBackgroundMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = CortanaColors.Accent,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = CortanaColors.Divider, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        text = "$label：${if (ok) "已就绪" else "未就绪"}",
        color = if (ok) CortanaColors.Success else CortanaColors.OnBackgroundMuted,
        fontSize = 13.sp,
    )
}
