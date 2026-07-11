package com.tetraploid.joyforold.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.system.NotificationAccessPermission
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset

import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DemoScreen(viewModel: DemoViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var demoInput by remember { mutableStateOf("") }
    var demoStatus by remember { mutableStateOf("等待 AI 操作") }
    var pendingVoiceAfterPermission by remember { mutableStateOf(false) }
    var pendingVoiceInputAfterPermission by remember { mutableStateOf(false) }
    val overlayRunning = remember { mutableStateOf(FloatingOverlayService.isRunning()) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.onRecordAudioPermissionResult(granted)
            if (granted) {
                when {
                    pendingVoiceAfterPermission -> viewModel.startVoiceReplyToConfirm()
                    pendingVoiceInputAfterPermission -> viewModel.startVoiceInput()
                }
            } else if (pendingVoiceAfterPermission || pendingVoiceInputAfterPermission) {
                viewModel.onVoicePermissionDenied()
            }
            pendingVoiceAfterPermission = false
            pendingVoiceInputAfterPermission = false
        },
    )

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> viewModel.onReadContactsPermissionResult(granted) },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityState()
                overlayRunning.value = FloatingOverlayService.isRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopVoiceInput()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("JoyForOld AI 操控", style = MaterialTheme.typography.headlineSmall)
            Text("模型：${uiState.modelName}", style = MaterialTheme.typography.bodySmall)
            Text(
                text = if (uiState.accessibilityEnabled) {
                    "无障碍服务：已开启"
                } else {
                    "无障碍服务：未开启（请先开启）"
                },
                color = if (uiState.accessibilityEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = if (uiState.recordAudioGranted) {
                    "麦克风权限：已授予"
                } else {
                    "麦克风权限：未授予（语音识别与本地唤醒需要）"
                },
                color = if (uiState.recordAudioGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = if (uiState.readContactsGranted) {
                    "联系人权限：已授予"
                } else {
                    "联系人权限：未授予（按姓名拨号需要，也可在家人协助中配置）"
                },
                color = if (uiState.readContactsGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = if (uiState.notificationAccessGranted) {
                    "通知使用权：已开启（可读未读消息）"
                } else {
                    "通知使用权：未开启（读未读消息需要）"
                },
                color = if (uiState.notificationAccessGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("1. 打开无障碍设置")
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(OverlayPermission.createSettingsIntent(context))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("2. 开启悬浮窗权限")
            }

            OutlinedButton(
                onClick = {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !uiState.recordAudioGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (uiState.recordAudioGranted) {
                        "3. 麦克风权限已开启"
                    } else {
                        "3. 授予麦克风权限"
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                },
                enabled = !uiState.readContactsGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (uiState.readContactsGranted) {
                        "4. 联系人权限已开启"
                    } else {
                        "4. 授予联系人权限"
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(NotificationAccessPermission.createSettingsIntent(context))
                },
                enabled = !uiState.notificationAccessGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (uiState.notificationAccessGranted) {
                        "5. 通知使用权已开启"
                    } else {
                        "5. 开启通知使用权（读未读消息）"
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (OverlayPermission.canDrawOverlays(context)) {
                            FloatingOverlayService.start(context)
                            overlayRunning.value = true
                        } else {
                            context.startActivity(OverlayPermission.createSettingsIntent(context))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("启动悬浮助手")
                }
                OutlinedButton(
                    onClick = {
                        FloatingOverlayService.stop(context)
                        overlayRunning.value = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("关闭悬浮窗")
                }
            }

            Text(
                text = if (overlayRunning.value) {
                    "悬浮窗：已启动（可切换到其他 App 使用）"
                } else {
                    "悬浮窗：未启动"
                },
                color = if (overlayRunning.value) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Text("API 配置", style = MaterialTheme.typography.titleMedium)
            Text(
                "可在下方填写并保存，也可复制 local.properties.example 为 local.properties 后配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("DeepSeek", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepSeek API Key") },
                singleLine = true,
            )
            OutlinedButton(onClick = viewModel::saveApiKey) {
                Text("保存 DeepSeek 配置")
            }

            HorizontalDivider()

            Text("豆包语音识别", style = MaterialTheme.typography.titleSmall)
            Text(
                "新版填 API Key；旧版填 App ID + Access Token（二选一）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.asrApiKey,
                onValueChange = viewModel::updateAsrApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ASR API Key（新版）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.asrAppId,
                onValueChange = viewModel::updateAsrAppId,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ASR App ID（旧版）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.asrAccessToken,
                onValueChange = viewModel::updateAsrAccessToken,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ASR Access Token（旧版）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.asrResourceId,
                onValueChange = viewModel::updateAsrResourceId,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ASR Resource ID") },
                placeholder = { Text("例如 volc.bigasr.sauc.duration") },
                singleLine = true,
            )
            OutlinedButton(onClick = viewModel::saveAsrConfig) {
                Text("保存语音识别配置")
            }

            HorizontalDivider()
            Text("家人协助与预设指令", style = MaterialTheme.typography.titleMedium)
            Text(
                "这里配置家人手机号、紧急求助话术、家的地址，以及像“我要回家”这样的固定口令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.daughterPhone,
                onValueChange = viewModel::updateDaughterPhone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("女儿手机号") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.sonPhone,
                onValueChange = viewModel::updateSonPhone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("儿子手机号") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.emergencyPhone,
                onValueChange = viewModel::updateEmergencyPhone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("紧急联系人手机号") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.emergencyMessage,
                onValueChange = viewModel::updateEmergencyMessage,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("紧急求助短信内容") },
                minLines = 2,
            )
            OutlinedTextField(
                value = uiState.homeAddress,
                onValueChange = viewModel::updateHomeAddress,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("家的地址") },
                placeholder = { Text("例如：深圳市南山区xx小区xx栋xx室") },
                minLines = 2,
            )
            OutlinedTextField(
                value = uiState.presetPhraseGoHome,
                onValueChange = viewModel::updatePresetPhraseGoHome,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("回家预设说法（可多条）") },
                placeholder = { Text("例如：我要回家, 导航回家, 送我回家") },
                minLines = 2,
            )
            Text(
                "这里保存的是整句别名，不是关键词。命中后会直接走地图导航深链，不依赖页面点击。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::saveCaregiverSettings) {
                Text("保存家人协助与预设")
            }

            HorizontalDivider()
            Text("本地语音唤醒（Sherpa KWS）", style = MaterialTheme.typography.titleSmall)
            Text(
                "模型版本：${uiState.wakeWordModelVersion}（开发模式会自动预下载）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = uiState.wakeWordEnabled,
                    onCheckedChange = viewModel::setWakeWordEnabled,
                )
                Text(
                    if (uiState.wakeWordRunning) "已运行" else "未运行",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.wakeWordRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            uiState.wakeWordTestHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            uiState.lastWakeWordAtMs?.let { ts ->
                val keyword = uiState.lastWakeWordKeyword.orEmpty()
                Text(
                    text = "上次唤醒：$keyword（$ts）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "推荐用预设调参：平衡=灵敏检测+二次确认；灵敏=单次命中；防误触=高阈值+二次确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeWordSensitivityPreset.entries.forEach { preset ->
                    val selected = uiState.wakeWordPreset == preset
                    OutlinedButton(
                        onClick = { viewModel.applyWakeWordPreset(preset) },
                        modifier = Modifier.weight(1f),
                        enabled = !selected,
                    ) {
                        Text(if (selected) "${preset.label}✓" else preset.label)
                    }
                }
            }
            Text(
                "当前：score=${uiState.wakeWordKeywordScore}，threshold=${uiState.wakeWordKeywordThreshold}，" +
                    "二次确认=${uiState.wakeWordConfirmHits}次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = uiState.wakeWordSileroVadEnabled,
                        onCheckedChange = viewModel::setWakeWordSileroVadEnabled,
                    )
                    Text("VAD 统计", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = uiState.wakeWordSecondStageEnabled,
                        onCheckedChange = viewModel::setWakeWordSecondStageEnabled,
                    )
                    Text("二阶段唤醒", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "默认持续监听全量音频；二阶段复检可降误触但可能漏唤醒。中文词如「小艺小艺」效果通常更好。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.wakeWordPhrase,
                onValueChange = viewModel::updateWakeWordPhrase,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("唤醒词") },
                placeholder = { Text("例如：小艺小艺、Hey,Cortana") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.wakeWordKeywordScore.toString(),
                onValueChange = viewModel::updateWakeWordKeywordScore,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("命中分数 keywordsScore") },
                placeholder = { Text("默认 3.0；预设「平衡」会自动设置") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.wakeWordKeywordThreshold.toString(),
                onValueChange = viewModel::updateWakeWordKeywordThreshold,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("命中阈值 keywordsThreshold") },
                placeholder = { Text("默认 0.018；越低越易误触") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::saveWakeWordConfig, modifier = Modifier.weight(1f)) {
                    Text("保存唤醒词")
                }
                Button(onClick = viewModel::testWakeWord, modifier = Modifier.weight(1f)) {
                    Text("测试唤醒词")
                }
            }
            uiState.wakeWordCalibrationHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::startWakeWordCalibration,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.wakeWordCalibrationRunning,
                ) {
                    Text("开始标定")
                }
                Button(
                    onClick = viewModel::recordCalibrationStep,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.wakeWordCalibrationRunning,
                ) {
                    Text(
                        when (uiState.wakeWordCalibrationStep) {
                            0, 1, 2 -> "录制样本 ${uiState.wakeWordCalibrationStep + 1}/3"
                            3 -> "录制环境音"
                            else -> "完成标定"
                        },
                    )
                }
            }

            HorizontalDivider()
            Text("指令测试", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = uiState.command,
                onValueChange = viewModel::updateCommand,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("指令（可语音填充）") },
                placeholder = { Text("例如：发送消息：你好 / 点击设置 / 向下滚动") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (uiState.isListening) {
                            if (uiState.accessibilityEnabled) {
                                viewModel.stopVoiceInputAndRunAgent()
                            } else {
                                viewModel.stopVoiceInput()
                            }
                            return@Button
                        }
                        if (uiState.recordAudioGranted) {
                            viewModel.startVoiceInput()
                        } else {
                            pendingVoiceInputAfterPermission = true
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isListening) "结束并让 AI 执行" else "开始语音输入")
                }
                OutlinedButton(
                    onClick = { viewModel.updateCommand(uiState.speechText) },
                    enabled = uiState.speechText.isNotBlank() && !uiState.isListening,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("填入识别结果")
                }
            }
            if (uiState.isListening || uiState.speechText.isNotBlank() ||
                uiState.voiceInteractionState != VoiceInteractionState.Idle
            ) {
                Text(
                    text = when (uiState.voiceInteractionState) {
                        VoiceInteractionState.SpeakingPrompt -> "正在播报，请听完再说话…"
                        VoiceInteractionState.Listening -> {
                            if (uiState.speechText.isNotBlank()) "聆听中：${uiState.speechText}"
                            else "正在聆听…"
                        }
                        VoiceInteractionState.Processing -> "正在处理语音…"
                        VoiceInteractionState.Idle -> "识别文本：${uiState.speechText}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::runAgent,
                    enabled = !uiState.isRunning && uiState.accessibilityEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isRunning) "执行中..." else "让 AI 执行")
                }
                OutlinedButton(
                    onClick = viewModel::previewPageTree,
                    enabled = uiState.accessibilityEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("读取页面")
                }
            }

            if (uiState.isRunning) {
                Text(
                    text = buildString {
                        append("Agent 步骤 ${uiState.currentStep}")
                        if (uiState.statusMessage.isNotBlank()) append(" · ${uiState.statusMessage}")
                        if (uiState.isPaused) append("（已暂停）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (uiState.isPaused) viewModel.resumeAgent() else viewModel.pauseAgent()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (uiState.isPaused) "继续" else "暂停")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelAgent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("停止 Agent")
                    }
                }
            }

            if (uiState.recentMemories.isNotEmpty()) {
                Text("历史记忆（最近）", style = MaterialTheme.typography.titleSmall)
                uiState.recentMemories.take(3).forEach { memory ->
                    Text("· $memory", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (uiState.waitingForUserConfirm && uiState.confirmPrompt != null) {
                VoiceConfirmBanner(
                    prompt = uiState.confirmPrompt.orEmpty(),
                    isListening = uiState.isListening,
                    speechText = uiState.speechText,
                    onCancel = viewModel::clearPendingConfirmUI,
                )
            }

            HorizontalDivider()

            Text("演示操作区（可让 AI 操控这部分）", style = MaterialTheme.typography.titleMedium)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = false) {
                        testTag = "demo_area"
                        contentDescription = "演示操作区"
                    },
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "状态：$demoStatus",
                        modifier = Modifier.semantics { contentDescription = "演示状态" },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { demoStatus = "您点击了设置" },
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "设置"
                            },
                        ) { Text("设置") }
                        Button(
                            onClick = { demoStatus = "您点击了打电话" },
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "打电话"
                            },
                        ) { Text("打电话") }
                        Button(
                            onClick = { demoStatus = "您点击了消息" },
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "消息"
                            },
                        ) { Text("消息") }
                    }

                    OutlinedTextField(
                        value = demoInput,
                        onValueChange = { demoInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = "演示输入框"
                            },
                        label = { Text("演示输入框") },
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .semantics { contentDescription = "演示列表" },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..8).forEach { index ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "演示列表第${index}项"
                                    },
                            ) {
                                Text(
                                    text = "演示列表第 $index 项",
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            uiState.logs.takeLast(20).forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
