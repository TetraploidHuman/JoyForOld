package com.tetraploid.joyforold.agent

/**
 * 通用 finish 校验：防止 Agent 在目标未达成时提前结束或谎报结果。
 * 不针对特定业务（音乐/购物等），只依据「指令语义 + 已执行步骤 + 页面状态」。
 */
object AgentFinishGuard {
    private val accomplishmentPhrases = listOf(
        "已开始", "已经开始", "正在", "已完成", "完成了", "成功了", "好了",
        "已打开", "已发送", "已播放", "已找到", "已进入", "已切换",
    )

    private val selectionIntentPattern = Regex(
        "(听|看|播放|放|播|打开|找|选|买|订|发|给|联系|拨打|呼叫|进入|查看).{1,40}",
    )

    private val searchPageCues = listOf(
        "搜索", "search", "结果", "综合", "筛选", "排序",
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

        inputWithoutSelectionReason(session, snapshot, rootCommand)?.let { return it }
        unsubstantiatedClaimReason(session, snapshot, rootCommand, message)?.let { return it }

        return null
    }

    /** 已输入搜索词但尚未 click 选中目标 */
    private fun inputWithoutSelectionReason(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!impliesTargetSelection(rootCommand)) return null
        val lastType = session.stepRecords.lastOrNull {
            it.action.action.equals("type", ignoreCase = true) && it.result.success
        } ?: return null

        if (hasSelectionClickAfter(session, lastType.step)) return null

        val typed = lastType.action.inputText?.trim().orEmpty()
        if (typed.length < 2) return null
        if (snapshot == null) return null

        val corpus = pageCorpus(snapshot)
        if (!corpus.contains(typed.lowercase())) return null

        val onSearchLikePage = searchPageCues.any { corpus.contains(it) } ||
            snapshot.clickables.size >= 4
        if (!onSearchLikePage) return null

        val targets = matchingTargets(snapshot, typed)
        return buildString {
            append("系统判定：已输入「$typed」，但尚未 click 选中目标。")
            append(" 用户指令需要与该项交互，输入/搜索只是中间步骤。")
            if (targets.isNotEmpty()) {
                append(" 页面上可尝试 click：${targets.joinToString("、")}。")
            }
            append(" 请 click 结果后再根据页面变化决定是否 finish。")
        }
    }

    /** finish 文案声称已完成，但缺少支撑步骤或页面依据 */
    private fun unsubstantiatedClaimReason(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
        message: String,
    ): String? {
        if (!claimsAccomplishment(message)) return null

        val interactiveSteps = session.stepRecords.count { step ->
            step.result.success && step.action.action.lowercase() in INTERACTIVE_ACTIONS
        }
        if (interactiveSteps == 0) {
            return "系统判定：finish 消息声称已完成，但本轮尚未有成功的交互操作（click/open_app/send 等）。请继续执行。"
        }

        if (!impliesTargetSelection(rootCommand)) return null

        val targetPhrase = extractTargetPhrase(rootCommand) ?: return null
        val lastType = session.stepRecords.lastOrNull {
            it.action.action.equals("type", ignoreCase = true) && it.result.success
        }
        if (lastType != null && !hasSelectionClickAfter(session, lastType.step)) {
            val targets = matchingTargets(snapshot, targetPhrase)
            return buildString {
                append("系统判定：你声称「$message」，但仅完成输入/搜索，尚未 click 选中「$targetPhrase」。")
                append(" finish 必须基于页面可见结果，不能推测。")
                if (targets.isNotEmpty()) append(" 可 click：${targets.joinToString("、")}。")
            }
        }

        if (snapshot != null && targetPhrase.length >= 2) {
            val corpus = pageCorpus(snapshot)
            val targetLower = targetPhrase.lowercase()
            val messageClaimsTarget = message.contains(targetPhrase, ignoreCase = true) ||
                corpus.contains(targetLower)
            if (!messageClaimsTarget && !corpus.contains(targetLower)) {
                return "系统判定：finish 消息与页面状态不一致。页面上未见「$targetPhrase」相关结果，请继续操作或改用 find_on_page/read_tree。"
            }
        }

        return null
    }

    fun impliesTargetSelection(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return false
        return selectionIntentPattern.containsMatchIn(trimmed)
    }

    fun extractTargetPhrase(command: String): String? {
        var text = command.trim()
        val prefixes = listOf(
            "我要", "我想", "帮我", "请", "麻烦", "能不能", "可以",
        ).sortedByDescending { it.length }
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        val verbs = listOf(
            "播放一首", "播放", "打开", "查看", "联系", "拨打", "呼叫",
            "听一首", "放一首", "播一首", "听", "看", "放", "播", "找", "选", "买", "订", "发", "给", "进入",
        ).sortedByDescending { it.length }
        for (verb in verbs) {
            val idx = text.indexOf(verb, ignoreCase = true)
            if (idx < 0) continue
            val remainder = text.substring(idx + verb.length).trim()
                .trim('《', '》', '"', '"', '"', '\'', ' ', '：', ':', '。', '.')
            if (remainder.length >= 2) return remainder
        }
        return null
    }

    private fun claimsAccomplishment(message: String): Boolean {
        if (message.isBlank()) return false
        return accomplishmentPhrases.any { message.contains(it) }
    }

    private fun hasSelectionClickAfter(session: AgentConversationSession, afterStep: Int): Boolean {
        return session.stepRecords.any { step ->
            step.step > afterStep &&
                step.action.action.equals("click", ignoreCase = true) &&
                step.result.success &&
                !step.action.targetText.orEmpty().contains("搜索", ignoreCase = true)
        }
    }

    fun matchingTargets(snapshot: StructuredPageSnapshot?, phrase: String): List<String> {
        if (snapshot == null || phrase.isBlank()) return emptyList()
        val needle = phrase.lowercase()
        val short = needle.take(4)
        return (snapshot.clickables + snapshot.visibleTexts)
            .filter { label ->
                val lower = label.lowercase()
                lower.contains(needle) || needle.contains(lower) ||
                    (short.length >= 2 && lower.contains(short))
            }
            .distinct()
            .take(6)
    }

    private fun pageCorpus(snapshot: StructuredPageSnapshot): String {
        return (snapshot.clickables + snapshot.visibleTexts + snapshot.editables + snapshot.sendButtons)
            .joinToString(" ")
            .lowercase()
    }

    private val INTERACTIVE_ACTIONS = setOf(
        "click", "type", "send", "open_app", "scroll_down", "scroll_up", "swipe_down",
    )
}
