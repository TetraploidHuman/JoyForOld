package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ui.cortana.CortanaOrb
import com.tetraploid.joyforold.ui.theme.CortanaColors

@Composable
fun AboutPage(
    uiState: AgentUiState,
    modifier: Modifier = Modifier,
) {
    var demoInput by remember { mutableStateOf("") }
    var demoStatus by remember { mutableStateOf("等待 AI 操作") }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CortanaColors.Background)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CortanaOrb(size = 64.dp, active = false)
        Text(
            text = "JoyForOld",
            color = CortanaColors.OnBackground,
            fontSize = 22.sp,
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME} · 为长辈设计的 AI 助手",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = 13.sp,
        )
        Text(
            text = "模型：${uiState.modelName}",
            color = CortanaColors.OnBackgroundSecondary,
            fontSize = 13.sp,
        )

        if (uiState.recentMemories.isNotEmpty()) {
            Text(
                text = "历史记忆",
                color = CortanaColors.Accent,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            uiState.recentMemories.take(5).forEach { memory ->
                Text("· $memory", color = CortanaColors.OnBackgroundMuted, fontSize = 12.sp)
            }
        }

        Text(
            text = "演示操作区（可让 AI 操控这部分）",
            color = CortanaColors.Accent,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CortanaColors.Surface),
            shape = RoundedCornerShape(4.dp),
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
                    color = CortanaColors.OnBackgroundSecondary,
                    modifier = Modifier.semantics { contentDescription = "演示状态" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { demoStatus = "您点击了设置" },
                        colors = ButtonDefaults.buttonColors(containerColor = CortanaColors.Accent),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "设置"
                        },
                    ) { Text("设置") }
                    Button(
                        onClick = { demoStatus = "您点击了打电话" },
                        colors = ButtonDefaults.buttonColors(containerColor = CortanaColors.Accent),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "打电话"
                        },
                    ) { Text("打电话") }
                    Button(
                        onClick = { demoStatus = "您点击了消息" },
                        colors = ButtonDefaults.buttonColors(containerColor = CortanaColors.Accent),
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
                        .semantics(mergeDescendants = true) { contentDescription = "演示输入框" },
                    label = { Text("演示输入框") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CortanaColors.OnBackground,
                        unfocusedTextColor = CortanaColors.OnBackground,
                        focusedBorderColor = CortanaColors.Accent,
                        unfocusedBorderColor = CortanaColors.Divider,
                    ),
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
                            colors = CardDefaults.cardColors(containerColor = CortanaColors.SurfaceElevated),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "演示列表第${index}项"
                                },
                        ) {
                            Text(
                                text = "演示列表第 $index 项",
                                color = CortanaColors.OnBackgroundSecondary,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
