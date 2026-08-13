package com.tetraploid.joyforold.ui.cortana

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tetraploid.joyforold.agent.VisionDebugFrame
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun VisionDebugGallery(
    enabled: Boolean,
    frames: List<VisionDebugFrame>,
    onEnabledChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewFrame by remember { mutableStateOf<VisionDebugFrame?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "开启后，每次 Agent 视觉规划会保存「发给 AI 的截图」；" +
                "若 AI 规划 tap/send 坐标，会在同一张图上用红圈标出点击位置。",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
        )
        RowWithSwitch(
            label = "保存视觉调试截图",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("刷新列表")
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("清空")
            }
        }
        if (frames.isEmpty()) {
            Text(
                text = if (enabled) "暂无截图。运行一次 Agent 任务后点「刷新列表」。" else "请先开启上方开关。",
                color = CortanaColors.OnBackgroundMuted,
                fontSize = JoyTextSizes.Caption,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(frames, key = { it.id }) { frame ->
                    VisionDebugThumb(
                        frame = frame,
                        onClick = { previewFrame = frame },
                    )
                }
            }
        }
    }

    previewFrame?.let { frame ->
        Dialog(onDismissRequest = { previewFrame = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CortanaColors.Background.copy(alpha = 0.96f))
                    .padding(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(frame.label, color = CortanaColors.OnBackground, fontSize = JoyTextSizes.BodySecondary)
                    Text(
                        frame.filePath,
                        color = CortanaColors.OnBackgroundMuted,
                        fontSize = JoyTextSizes.Hint,
                    )
                    val bitmap = remember(frame.filePath) {
                        BitmapFactory.decodeFile(frame.filePath)?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = frame.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("无法加载图片", color = CortanaColors.OnBackgroundMuted)
                    }
                    TextButton(
                        onClick = { previewFrame = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("关闭", color = CortanaColors.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun VisionDebugThumb(
    frame: VisionDebugFrame,
    onClick: () -> Unit,
) {
    val bitmap = remember(frame.filePath) {
        BitmapFactory.decodeFile(frame.filePath)?.asImageBitmap()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(CortanaColors.SurfaceElevated)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(frame.label, color = CortanaColors.OnBackground, fontSize = JoyTextSizes.Caption)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = frame.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun RowWithSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CortanaColors.OnBackground, fontSize = JoyTextSizes.BodySecondary)
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = CortanaColors.Accent,
                checkedTrackColor = CortanaColors.SurfaceElevated,
            ),
        )
    }
}
