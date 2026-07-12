package com.tetraploid.joyforold.agent

/**
 * 从近期步骤构建「禁止重复」清单（对齐 DroidLM doNotRepeat），注入每轮 user 消息。
 */
data class AgentDoNotRepeatEntry(
    val actionKey: String,
    val description: String,
    val reason: String,
)

object AgentDoNotRepeat {
    fun buildFrom(session: AgentConversationSession): List<AgentDoNotRepeatEntry> {
        return session.stepRecords
            .asReversed()
            .filter { shouldAvoidRepeating(it) }
            .distinctBy { AgentActionGuard.actionKey(it.action) }
            .take(8)
            .map { step ->
                AgentDoNotRepeatEntry(
                    actionKey = AgentActionGuard.actionKey(step.action),
                    description = describe(step.action),
                    reason = repeatReason(step),
                )
            }
    }

    fun formatForPrompt(entries: List<AgentDoNotRepeatEntry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("【禁止重复的操作】")
            entries.forEachIndexed { index, entry ->
                append(index + 1).append(". ").append(entry.description)
                append(" — ").append(entry.reason)
                appendLine()
            }
            append("以上操作已失败或无页面进展，必须换完全不同策略。")
        }.trimEnd()
    }

    private fun shouldAvoidRepeating(step: AgentStepRecord): Boolean {
        if (!step.result.success) return true
        return AgentActionGuard.pageDiffIndicatesNoChange(step.pageDiff)
    }

    private fun repeatReason(step: AgentStepRecord): String {
        if (!step.result.success) {
            return step.result.summary.ifBlank { "执行失败" }
        }
        return "已成功但页面无明显变化"
    }

    private fun describe(action: AgentAction): String {
        val target = action.targetText?.let { "「$it」" }.orEmpty()
        val input = action.inputText?.let { " 输入=${it.take(20)}" }.orEmpty()
        return "${action.action}$target$input"
    }
}
