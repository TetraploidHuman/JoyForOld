package com.tetraploid.joyforold.ui

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission

import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DemoScreen(viewModel: DemoViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var demoInput by remember { mutableStateOf("") }
    var demoStatus by remember { mutableStateOf("等待 AI 操作") }
    var pendingVoiceAfterPermission by remember { mutableStateOf(false) }
    val overlayRunning = remember { mutableStateOf(FloatingOverlayService.isRunning()) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                if (pendingVoiceAfterPermission) {
                    viewModel.startVoiceReplyToConfirm()
                } else {
                    viewModel.startVoiceInput()
                }
            } else {
                viewModel.onVoicePermissionDenied()
            }
            pendingVoiceAfterPermission = false
        },
    )

    LaunchedEffect(uiState.waitingForUserConfirm, uiState.confirmPrompt) {
        if (!uiState.waitingForUserConfirm || uiState.isListening || uiState.isRunning) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startVoiceReplyToConfirm()
        } else {
            pendingVoiceAfterPermission = true
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

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

            Text("本页内测试区", style = MaterialTheme.typography.titleMedium)
            Text(
                "API 密钥请在 local.properties 配置（见仓库内 local.properties.example），也可在下方保存 DeepSeek Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepSeek API Key") },
                singleLine = true,
            )
            OutlinedButton(onClick = viewModel::saveApiKey) {
                Text("保存 API Key")
            }

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
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.startVoiceInput()
                        } else {
                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
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
            if (uiState.speechText.isNotBlank()) {
                Text(
                    text = "识别文本：${uiState.speechText}",
                    style = MaterialTheme.typography.bodySmall,
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
