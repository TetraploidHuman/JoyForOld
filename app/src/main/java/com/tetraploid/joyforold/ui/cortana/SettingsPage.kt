package com.tetraploid.joyforold.ui.cortana

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.accessibility.WeChatA11yComponent
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ime.JoyImeHelper
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission
import com.tetraploid.joyforold.system.NotificationAccessPermission
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset

@Composable
fun SettingsPage(
    uiState: AgentUiState,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    overlayRunning: Boolean,
    onRequestAudioPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onUpdateWakeWordPhrase: (String) -> Unit,
    onApplyWakeWordPreset: (WakeWordSensitivityPreset) -> Unit,
    onSetWakeWordEnabled: (Boolean) -> Unit,
    onSaveWakeWordConfig: () -> Unit,
    onSetCloudContextConsent: (Boolean) -> Unit,
    onSetVoiceBargeIn: (Boolean) -> Unit,
    onTestWakeWord: () -> Unit,
    onStartCalibration: () -> Unit,
    onRecordCalibrationStep: () -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onUpdateAsrApiKey: (String) -> Unit,
    onUpdateAsrAppId: (String) -> Unit,
    onUpdateAsrAccessToken: (String) -> Unit,
    onUpdateAsrResourceId: (String) -> Unit,
    onSaveAsrConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showApiConfig by rememberSaveable { mutableStateOf(false) }
    if (showApiConfig) {
        ApiConfigPage(
            uiState = uiState,
            onBack = { showApiConfig = false },
            onUpdateApiKey = onUpdateApiKey,
            onSaveApiKey = onSaveApiKey,
            onUpdateAsrApiKey = onUpdateAsrApiKey,
            onUpdateAsrAppId = onUpdateAsrAppId,
            onUpdateAsrAccessToken = onUpdateAsrAccessToken,
            onUpdateAsrResourceId = onUpdateAsrResourceId,
            onSaveAsrConfig = onSaveAsrConfig,
            modifier = modifier,
        )
        return
    }

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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("外观")
        SettingsSwitchRow(
            title = "深色模式",
            checked = darkTheme,
            onCheckedChange = onDarkThemeChange,
            hint = if (darkTheme) "当前为深色界面" else "当前为亮色界面",
        )

        SectionDivider()
        SectionTitle("接口")
        OutlinedButton(
            onClick = { showApiConfig = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("配置 API（大模型 / 语音识别）")
        }
        Text(
            text = apiConfigSummary(uiState),
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )

        SectionDivider()
        SectionTitle("权限与服务")
        StatusLine("无障碍（主服务）", uiState.accessibilityEnabled && uiState.accessibilityServiceConnected)
        if (uiState.accessibilityEnabled && !uiState.accessibilityServiceConnected) {
            HintText("已开启，服务连接中…")
        }
        StatusLine("麦克风", uiState.recordAudioGranted)
        StatusLine("联系人", uiState.readContactsGranted)
        StatusLine("通知使用权", uiState.notificationAccessGranted)
        StatusLine("悬浮助手", overlayRunning)
        JoyImeStatusLine(
            enabled = uiState.joyImeEnabled,
            selectedAsDefault = uiState.joyImeSelectedAsDefault,
        )
        HintText("若曾「强制停止」本应用，无障碍会自动关闭，需再打开一次。")

        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开无障碍设置") }
        OutlinedButton(
            onClick = { context.startActivity(OverlayPermission.createSettingsIntent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (OverlayPermission.canDrawOverlays(context)) "悬浮窗权限已开启" else "开启悬浮窗权限") }
        if (!uiState.recordAudioGranted) {
            OutlinedButton(
                onClick = onRequestAudioPermission,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("授予麦克风权限") }
        }
        if (!uiState.readContactsGranted) {
            OutlinedButton(
                onClick = onRequestContactsPermission,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("授予联系人权限") }
        }
        if (!uiState.notificationAccessGranted) {
            OutlinedButton(
                onClick = {
                    context.startActivity(NotificationAccessPermission.createSettingsIntent(context))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开启通知使用权") }
        }

        SectionDivider()
        SectionTitle("组件")
        WeChatSupportComponentCard(
            status = WeChatA11yComponent.status(context),
            onOpenSettings = {
                context.startActivity(WeChatA11yComponent.openAccessibilitySettingsIntent())
            },
        )
        OutlinedButton(
            onClick = { context.startActivity(JoyImeHelper.createSettingsIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (uiState.joyImeSelectedAsDefault) {
                    "Joy 输入助手（已设默认）"
                } else {
                    "启用 Joy 输入助手（可选）"
                },
            )
        }
        HintText(stringResource(com.tetraploid.joyforold.R.string.joy_ime_settings_hint))

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
        SectionTitle("隐私")
        HintText("开启后，助手可将当前屏幕结构发到云端，用于发消息、点按钮等。仅在使用相关功能时上传。")
        SettingsSwitchRow(
            title = "允许云端理解屏幕内容",
            checked = uiState.cloudContextConsentGranted,
            onCheckedChange = onSetCloudContextConsent,
        )

        SectionDivider()
        SectionTitle("语音")
        HintText("开启后，播报时可直接说话打断，无需等播完。")
        SettingsSwitchRow(
            title = "播报时可语音打断",
            checked = uiState.voiceBargeInEnabled,
            onCheckedChange = onSetVoiceBargeIn,
        )

        SectionDivider()
        SectionTitle("本地语音唤醒")
        HintText("模型：${uiState.wakeWordModelVersion}")
        SettingsSwitchRow(
            title = if (uiState.wakeWordRunning) "唤醒服务已运行" else "开启本地唤醒",
            checked = uiState.wakeWordEnabled,
            onCheckedChange = onSetWakeWordEnabled,
        )
        uiState.wakeWordTestHint?.let { HintText(it, accent = true) }
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
        OutlinedTextField(
            value = uiState.wakeWordPhrase,
            onValueChange = onUpdateWakeWordPhrase,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("唤醒词") },
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
        uiState.wakeWordCalibrationHint?.let { HintText(it, accent = true) }
    }
}

private fun apiConfigSummary(uiState: AgentUiState): String {
    val llm = if (uiState.apiKey.isNotBlank()) "LLM 已配置" else "LLM 未配置"
    val asr = when {
        uiState.asrApiKey.isNotBlank() -> "语音识别已配置（新版）"
        uiState.asrAppId.isNotBlank() && uiState.asrAccessToken.isNotBlank() ->
            "语音识别已配置（旧版）"
        else -> "语音识别未配置"
    }
    return "$llm · $asr"
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.Body,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CortanaColors.Accent,
                    checkedTrackColor = CortanaColors.SurfaceElevated,
                ),
            )
        }
        if (!hint.isNullOrBlank()) {
            HintText(hint)
        }
    }
}

@Composable
private fun WeChatSupportComponentCard(
    status: WeChatA11yComponent.Status,
    onOpenSettings: () -> Unit,
) {
    val statusColor = when (status) {
        WeChatA11yComponent.Status.ACTIVE -> CortanaColors.Success
        WeChatA11yComponent.Status.PENDING -> CortanaColors.OnBackgroundSecondary
        WeChatA11yComponent.Status.OFF -> CortanaColors.OnBackgroundMuted
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = WeChatA11yComponent.DISPLAY_NAME,
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.Body,
            )
            Text(
                text = WeChatA11yComponent.statusLabel(status),
                color = statusColor,
                fontSize = JoyTextSizes.Caption,
            )
        }
        HintText(WeChatA11yComponent.statusHint(status))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (status) {
                    WeChatA11yComponent.Status.ACTIVE -> "管理无障碍服务"
                    WeChatA11yComponent.Status.PENDING -> "打开无障碍设置"
                    WeChatA11yComponent.Status.OFF -> "启用微信支持组件"
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = CortanaColors.Accent,
        fontSize = JoyTextSizes.Label,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = CortanaColors.Divider, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun HintText(text: String, accent: Boolean = false) {
    Text(
        text = text,
        color = if (accent) CortanaColors.AccentMuted else CortanaColors.OnBackgroundMuted,
        fontSize = JoyTextSizes.Caption,
        lineHeight = JoyTextSizes.CaptionLineHeight,
    )
}

@Composable
private fun JoyImeStatusLine(enabled: Boolean, selectedAsDefault: Boolean) {
    val (text, ok) = when {
        selectedAsDefault ->
            "Joy 输入助手：已就绪" to true
        enabled ->
            "Joy 输入助手：已启用（可选设为默认）" to false
        else -> "Joy 输入助手：未启用（可选）" to false
    }
    Text(
        text = text,
        color = if (ok) CortanaColors.Success else CortanaColors.OnBackgroundMuted,
        fontSize = JoyTextSizes.Caption,
    )
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        text = "$label：${if (ok) "已就绪" else "未就绪"}",
        color = if (ok) CortanaColors.Success else CortanaColors.OnBackgroundMuted,
        fontSize = JoyTextSizes.BodySecondary,
        lineHeight = JoyTextSizes.BodyLineHeight,
    )
}
