package com.tetraploid.joyforold.agent

/**
 * Mantis 式循环状态：连续无变化 / 相同动作计数，注入 planner user 消息。
 */
data class AgentLoopState(
    var noChangeCount: Int = 0,
    var sameActionCount: Int = 0,
    var lastActionKey: String = "",
) {
    fun afterStep(action: AgentAction, pageDiff: String) {
        if (AgentActionGuard.pageDiffIndicatesNoChange(pageDiff)) {
            noChangeCount++
        } else {
            noChangeCount = 0
        }
        val key = AgentActionGuard.actionKey(action)
        if (key.isNotBlank() && key == lastActionKey) {
            sameActionCount++
        } else {
            sameActionCount = 0
            lastActionKey = key
        }
    }

    companion object {
        fun formatWarnings(state: AgentLoopState, stepNo: Int, maxSteps: Int): String {
            val parts = mutableListOf<String>()
            parts += "【循环状态】第 $stepNo/$maxSteps 步"
            if (state.noChangeCount >= 1) {
                parts += "⚠️ 页面已连续 ${state.noChangeCount} 步无明显变化，上一步可能无效；" +
                    "若已在视频详情页且标题匹配请 finish，否则 read_tree 换目标，勿重复相同 click/type。"
            }
            if (state.sameActionCount >= 2) {
                parts += "🔁 相同操作已连续 ${state.sameActionCount} 次，必须换策略（滚动/换控件/换应用）。"
            }
            return parts.joinToString("\n")
        }

        fun formatPlannerContext(
            state: AgentLoopState,
            session: AgentConversationSession,
            stepNo: Int,
            maxSteps: Int,
        ): String {
            return buildString {
                appendLine(formatWarnings(state, stepNo, maxSteps))
                val doNotRepeat = AgentDoNotRepeat.formatForPrompt(AgentDoNotRepeat.buildFrom(session))
                if (doNotRepeat.isNotBlank()) {
                    appendLine()
                    append(doNotRepeat)
                }
            }.trimEnd()
        }
    }
}
