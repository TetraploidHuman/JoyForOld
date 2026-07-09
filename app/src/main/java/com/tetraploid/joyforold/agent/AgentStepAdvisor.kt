package com.tetraploid.joyforold.agent

/**
 * 通用步骤后建议：根据指令语义与页面状态，提示 Agent 下一步，不绑定具体 App/场景。
 */
object AgentStepAdvisor {
    fun postStepHint(
        session: AgentConversationSession,
        action: AgentAction,
        result: ActionExecutionResult,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!result.success) return null
        return when (action.action.lowercase()) {
            "type" -> hintAfterType(action, snapshot, rootCommand)
            "find_on_page" -> hintAfterFind(result, snapshot, rootCommand)
            "open_app" -> hintAfterOpenApp(rootCommand)
            "click" -> hintAfterClick(session, action, snapshot, rootCommand)
            else -> null
        }
    }

    private fun hintAfterType(
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!AgentFinishGuard.impliesTargetSelection(rootCommand)) return null
        val typed = action.inputText?.trim().orEmpty()
        if (typed.length < 2) return null
        val targets = AgentFinishGuard.matchingTargets(snapshot, typed)
        return buildString {
            append("【步骤建议】已输入文字。")
            append("若用户目标是对该项进行操作，通常还需 click 列表或按钮中的目标。")
            if (targets.isNotEmpty()) append(" 可尝试：${targets.joinToString("、")}。")
            append(" 勿在未选中/未进入目标页时 finish。")
        }
    }

    private fun hintAfterFind(
        result: ActionExecutionResult,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (result.matchedElements.isEmpty()) return null
        if (!AgentFinishGuard.impliesTargetSelection(rootCommand)) return null
        return buildString {
            append("【步骤建议】已找到匹配项：${result.matchedElements.take(4).joinToString("、")}。")
            append(" 下一步应 click 其中最符合用户指令的一项。")
        }
    }

    private fun hintAfterOpenApp(rootCommand: String): String {
        return "【步骤建议】应用已打开。继续在应用内完成用户目标，根据页面快览操作，勿立即 finish。"
    }

    private fun hintAfterClick(
        session: AgentConversationSession,
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!AgentFinishGuard.impliesTargetSelection(rootCommand)) return null
        val target = action.targetText?.trim().orEmpty()
        if (target.contains("搜索", ignoreCase = true)) {
            val phrase = AgentFinishGuard.extractTargetPhrase(rootCommand) ?: return null
            val targets = AgentFinishGuard.matchingTargets(snapshot, phrase)
            if (targets.isEmpty()) return null
            return "【步骤建议】已触发搜索。下一步 click 列表中的目标（${targets.joinToString("、")}）。"
        }
        return null
    }
}
