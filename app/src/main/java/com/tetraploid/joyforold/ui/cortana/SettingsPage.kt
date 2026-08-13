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
        SectionTitle("外观")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "深色模式",
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.Body,
            )
            Switch(
                checked = darkTheme,
                onCheckedChange = onDarkThemeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CortanaColors.Accent,
                    checkedTrackColor = CortanaColors.SurfaceElevated,
                ),
            )
        }
        Text(
            text = if (darkTheme) "当前为深色界面" else "当前为亮色界面",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            modifier = Modifier.padding(start = 4.dp),
        )
        SectionDivider()
        SectionTitle("权限与服务")
        StatusLine("无障碍（主服务）", uiState.accessibilityEnabled)
        if (uiState.accessibilityEnabled && !uiState.accessibilityServiceConnected) {
            Text(
                text = "已开启，服务连接中…",
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = JoyTextSizes.Caption,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = "提示：如果在系统设置里「强制停止」过本应用，无障碍会自动关闭，需要再打开一次。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        SectionDivider()
        SectionTitle("组件")
        WeChatSupportComponentCard(
            status = WeChatA11yComponent.status(context),
            onOpenSettings = {
                context.startActivity(WeChatA11yComponent.openAccessibilitySettingsIntent())
            },
        )

        StatusLine("麦克风", uiState.recordAudioGranted)
        StatusLine("联系人", uiState.readContactsGranted)
        StatusLine("通知使用权", uiState.notificationAccessGranted)
        StatusLine("悬浮助手", overlayRunning)
        JoyImeStatusLine(
            enabled = uiState.joyImeEnabled,
            selectedAsDefault = uiState.joyImeSelectedAsDefault,
        )
        if (uiState.joyImeEnabled && !uiState.joyImeSelectedAsDefault) {
            Text(
                text = "已启用但未设默认：助手会用粘贴输入；设为默认可提高微信等自动输入成功率。",
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = JoyTextSizes.Caption,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = stringResource(com.tetraploid.joyforold.R.string.joy_ime_settings_hint),
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开无障碍设置") }
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
        SectionTitle("隐私与权限")
        Text(
            text = "开启后，助手可将当前屏幕结构发送到云端，用于微信发消息、点按钮等 UI 自动化。仅在使用相关功能时上传，不会持续上传。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "允许云端理解屏幕内容",
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.BodySecondary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = uiState.cloudContextConsentGranted,
                onCheckedChange = onSetCloudContextConsent,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CortanaColors.Accent,
                    checkedTrackColor = CortanaColors.SurfaceElevated,
                ),
            )
        }

        SectionDivider()
        SectionTitle("语音对话")
        Text(
            text = "开启后，助手播报时可直接说话打断，无需等播完。本地检测人声后停播并开麦；若仍有回声，会自动过滤已播报内容。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播报时可语音打断",
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.BodySecondary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = uiState.voiceBargeInEnabled,
                onCheckedChange = onSetVoiceBargeIn,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CortanaColors.Accent,
                    checkedTrackColor = CortanaColors.SurfaceElevated,
                ),
            )
        }

        SectionDivider()
        SectionTitle("本地语音唤醒")
        Text(
            "模型：${uiState.wakeWordModelVersion}",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
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
                fontSize = JoyTextSizes.Caption,
            )
        }
        uiState.wakeWordTestHint?.let {
            Text(it, color = CortanaColors.AccentMuted, fontSize = JoyTextSizes.Caption)
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
        uiState.wakeWordCalibrationHint?.let {
            Text(it, color = CortanaColors.AccentMuted, fontSize = JoyTextSizes.Caption)
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
        Text(
            text = "内置组件 · 随主应用一起安装",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
        )
        Text(
            text = WeChatA11yComponent.statusHint(status),
            color = CortanaColors.OnBackgroundSecondary,
            fontSize = JoyTextSizes.Caption,
        )
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
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = CortanaColors.Divider, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun JoyImeStatusLine(enabled: Boolean, selectedAsDefault: Boolean) {
    val (text, color) = when {
        selectedAsDefault ->
            "Joy 输入助手：已就绪（自己打字会自动切回原键盘）" to CortanaColors.Success
        enabled ->
            "Joy 输入助手：已启用（可选设为默认）" to CortanaColors.OnBackgroundSecondary
        else -> "Joy 输入助手：未启用（可选）" to CortanaColors.OnBackgroundMuted
    }
    Text(text = text, color = color, fontSize = JoyTextSizes.Caption)
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
