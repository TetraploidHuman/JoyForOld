package com.tetraploid.joyforold.agent

data class ActionExecutionResult(
    val success: Boolean,
    val summary: String,
    val detail: String = "",
    val matchedElements: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
) {
    fun toAgentFeedback(): String = buildString {
        append(if (success) "成功" else "失败")
        append("：").append(summary)
        if (detail.isNotBlank() && detail != summary) {
            append("（")
            append(AgentMessageCompactor.truncateAgentFeedbackDetail(detail))
            append("）")
        }
        if (matchedElements.isNotEmpty()) {
            appendLine()
            append("匹配项：").append(matchedElements.take(12).joinToString(" | "))
        }
        if (suggestions.isNotEmpty()) {
            appendLine()
            append("建议：").append(suggestions.take(4).joinToString("；"))
        }
    }.trim()
}
