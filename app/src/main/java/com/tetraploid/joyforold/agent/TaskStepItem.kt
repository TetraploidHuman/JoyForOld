package com.tetraploid.joyforold.agent

enum class TaskStepStatus {
    Completed,
    InProgress,
    Pending,
}

data class TaskStepItem(
    val index: Int,
    val label: String,
    val status: TaskStepStatus,
)

object TaskStepTracker {
    fun buildProgressUpdate(
        existing: List<TaskStepItem>,
        stepNo: Int,
        message: String,
    ): List<TaskStepItem> {
        val updated = existing.map { item ->
            when {
                item.index < stepNo && item.status != TaskStepStatus.Completed ->
                    item.copy(status = TaskStepStatus.Completed)
                item.index > stepNo && item.status == TaskStepStatus.InProgress ->
                    item.copy(status = TaskStepStatus.Pending)
                else -> item
            }
        }.toMutableList()

        val currentIdx = updated.indexOfFirst { it.index == stepNo }
        val current = TaskStepItem(stepNo, message, TaskStepStatus.InProgress)
        if (currentIdx >= 0) {
            updated[currentIdx] = current
        } else {
            updated.add(current)
        }
        return updated.sortedBy { it.index }
    }

    fun fromPlannedActions(actions: List<AgentAction>): List<TaskStepItem> {
        return actions.mapIndexed { index, action ->
            val label = when {
                action.action.equals("finish", ignoreCase = true) -> action.message ?: "完成"
                action.message?.isNotBlank() == true -> action.message.orEmpty()
                else -> action.action
            }
            TaskStepItem(index + 1, label, TaskStepStatus.Pending)
        }
    }

    fun markAllCompleted(steps: List<TaskStepItem>): List<TaskStepItem> {
        return steps.map { it.copy(status = TaskStepStatus.Completed) }
    }

    fun completedCount(steps: List<TaskStepItem>): Int {
        return steps.count { it.status == TaskStepStatus.Completed }
    }
}
