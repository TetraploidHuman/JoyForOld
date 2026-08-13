package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.TaskPhaseItem
import com.tetraploid.joyforold.agent.TaskStepItem
import com.tetraploid.joyforold.agent.TaskStepStatus
import com.tetraploid.joyforold.agent.TaskStepTracker
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun TaskModePanel(
    phases: List<TaskPhaseItem>,
    detailSteps: List<TaskStepItem>,
    modifier: Modifier = Modifier,
    header: String? = null,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
) {
    if (phases.isEmpty() && detailSteps.isEmpty()) return

    val displayPhases = phases.ifEmpty {
        detailSteps.map { step ->
            TaskPhaseItem(step.index, step.label, step.status)
        }
    }
    val completed = displayPhases.count { it.status == TaskStepStatus.Completed }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header?.takeIf { it.isNotBlank() } ?: "任务进度",
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = JoyTextSizes.Caption,
            )
            Text(
                text = "$completed/${displayPhases.size}",
                color = CortanaColors.OnBackgroundMuted,
                fontSize = JoyTextSizes.Caption,
            )
        }

        displayPhases.forEach { phase ->
            TaskStepRow(
                label = phase.label,
                status = phase.status,
            )
        }

        if (detailSteps.isNotEmpty()) {
            ExpandDetailToggle(
                expanded = expanded,
                onClick = onToggleExpand,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    detailSteps.forEach { step ->
                        TaskStepRow(
                            label = step.label,
                            status = step.status,
                            fontSize = JoyTextSizes.Caption,
                            iconSize = 14.dp,
                            muted = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskStepList(
    steps: List<TaskStepItem>,
    modifier: Modifier = Modifier,
    header: String? = null,
    compact: Boolean = false,
) {
    if (steps.isEmpty()) return

    val completed = TaskStepTracker.completedCount(steps)
    val displaySteps = if (compact) {
        steps.filter { it.status == TaskStepStatus.InProgress }.take(1)
    } else {
        steps
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!header.isNullOrBlank() || compact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!header.isNullOrBlank()) {
                    Text(
                        text = header,
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = JoyTextSizes.Caption,
                    )
                } else {
                    Box(modifier = Modifier.weight(1f))
                }
                Text(
                    text = "$completed/${steps.size}",
                    color = CortanaColors.OnBackgroundMuted,
                    fontSize = JoyTextSizes.Caption,
                )
            }
        }

        displaySteps.forEach { step ->
            TaskStepRow(label = step.label, status = step.status)
        }
    }
}

@Composable
private fun TaskStepRow(
    label: String,
    status: TaskStepStatus,
    fontSize: androidx.compose.ui.unit.TextUnit = JoyTextSizes.BodySecondary,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    muted: Boolean = false,
) {
    val (iconColor, textColor, decoration) = when (status) {
        TaskStepStatus.Completed -> Triple(
            CortanaColors.StepCompleted,
            if (muted) CortanaColors.OnBackgroundMuted else CortanaColors.StepCompleted,
            TextDecoration.LineThrough,
        )
        TaskStepStatus.InProgress -> Triple(
            CortanaColors.Accent,
            if (muted) CortanaColors.OnBackgroundSecondary else CortanaColors.StepActive,
            TextDecoration.None,
        )
        TaskStepStatus.Pending -> Triple(
            CortanaColors.StepPending,
            CortanaColors.StepPending,
            TextDecoration.None,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (status) {
            TaskStepStatus.Completed -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize),
            )
            TaskStepStatus.InProgress -> Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize),
            )
            TaskStepStatus.Pending -> Icon(
                imageVector = Icons.Outlined.Circle,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize),
            )
        }
        Text(
            text = label,
            color = textColor,
            fontSize = fontSize,
            textDecoration = decoration,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun TaskStepProgressChip(
    phases: List<TaskPhaseItem>,
    steps: List<TaskStepItem>,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
) {
    if (phases.isEmpty() && steps.isEmpty()) return
    val activeLabel = phases.firstOrNull { it.status == TaskStepStatus.InProgress }?.label
        ?: steps.firstOrNull { it.status == TaskStepStatus.InProgress }?.label
    TaskModePanel(
        phases = phases,
        detailSteps = steps,
        modifier = modifier,
        header = activeLabel ?: "任务进度",
        expanded = expanded,
        onToggleExpand = onToggleExpand,
    )
}
