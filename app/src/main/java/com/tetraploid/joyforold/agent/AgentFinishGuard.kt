package com.tetraploid.joyforold.agent

/**
 * finish 的**客观**校验：不解析自然语言意图，只拦截明显与事实不符的结束。
 * 「任务是否完成」由 Agent（DeepSeek）结合用户指令与页面状态自行判断。
 */
object AgentFinishGuard {
    private val accomplishmentPhrases = listOf(
        "已开始", "已经开始", "正在", "已完成", "完成了", "成功了", "好了",
        "已打开", "已发送", "已播放", "已找到", "已进入", "已切换",
    )

    fun prematureFinishReason(
        session: AgentConversationSession,
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!action.action.equals("finish", ignoreCase = true) && !action.finished) return null
        if (action.waitingForUser) return null

        val message = action.message?.trim().orEmpty()
        if (message.contains("?") || message.contains("？")) return null

        return unsubstantiatedClaimReason(session, message)
            ?: finishContradictsPage(session, snapshot, message)
    }

    /** finish 声称的目标词不在当前页面快览中（客观事实校验，非意图解析） */
    private fun finishContradictsPage(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        message: String,
    ): String? {
        if (snapshot == null || message.isBlank()) return null
        val corpus = pageCorpus(snapshot)
        if (corpus.isBlank()) return null

        val quoted = Regex("《([^》]{2,})》").findAll(message).map { it.groupValues[1].trim() }.toList()
        for (title in quoted) {
            if (!corpus.contains(title.lowercase())) {
                return "系统判定：finish 声称「$title」，但当前页面快览未见该标题。可能点错了结果，请继续操作或 read_tree。"
            }
        }

        val typed = session.stepRecords.lastOrNull {
            it.action.action.equals("type", ignoreCase = true) && it.result.success
        }?.action?.inputText?.trim().orEmpty()
        if (typed.length >= 2 && message.contains(typed, ignoreCase = true) && !corpus.contains(typed.lowercase())) {
            return "系统判定：finish 声称已处理「$typed」，但当前页面快览未见「$typed」。可能点错了视频/条目，请继续操作。"
        }
        return null
    }

    private fun pageCorpus(snapshot: StructuredPageSnapshot): String {
        return (snapshot.clickables + snapshot.visibleTexts + snapshot.editables)
            .joinToString(" ")
            .lowercase()
    }

    /** finish 文案声称已完成，但本轮没有任何成功的交互步骤 */
    private fun unsubstantiatedClaimReason(
        session: AgentConversationSession,
        message: String,
    ): String? {
        if (!claimsAccomplishment(message)) return null

        val interactiveSteps = session.stepRecords.count { step ->
            step.result.success && step.action.action.lowercase() in INTERACTIVE_ACTIONS
        }
        if (interactiveSteps == 0) {
            return "系统判定：finish 消息声称已完成，但本轮尚未有成功的交互操作（click/open_app/send 等）。请继续执行。"
        }
        return null
    }

    private fun claimsAccomplishment(message: String): Boolean {
        if (message.isBlank()) return false
        return accomplishmentPhrases.any { message.contains(it) }
    }

    private val INTERACTIVE_ACTIONS = setOf(
        "click", "type", "send", "open_app", "scroll_down", "scroll_up", "swipe_down",
    )
}
