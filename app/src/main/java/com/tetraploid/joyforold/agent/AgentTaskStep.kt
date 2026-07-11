package com.tetraploid.joyforold.agent

enum class AgentTaskStepStatus {
    Completed,
    InProgress,
    Pending,
}

data class AgentTaskStep(
    val index: Int,
    val label: String,
    val status: AgentTaskStepStatus,
)
