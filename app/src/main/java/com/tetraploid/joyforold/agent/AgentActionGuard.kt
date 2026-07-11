package com.tetraploid.joyforold.agent

/**
 * 辅助 AI 决策（非替代）：拦截明显重复失败、补问敏感操作。
 */
object AgentActionGuard {
    private val callButtonKeywords = listOf(
        "拨打", "拨号", "通话", "呼叫", "语音通话", "视频通话", "语音电话", "视频电话",
    )
    private val sendKeywords = listOf("发送", "send", "发表", "送出")

    private const val CALL_ROUTE_PROMPT = "你要在哪里打电话？请说 QQ电话 或 手机电话。"
    private const val SEND_PROMPT = "即将发送消息。请确认：要说「发送」还是「取消」？"
    private const val SEND_CLICK_PROMPT = "即将点击发送。请确认：要说「发送」还是「取消」？"

    fun actionKey(action: AgentAction): String {
        return buildString {
            append(action.action.lowercase())
            action.targetText?.trim()?.lowercase()?.let { append("|").append(it) }
            action.inputText?.trim()?.take(40)?.lowercase()?.let { append("|").append(it) }
        }
    }

    /** 相同操作已连续失败达到阈值 → 不再执行，直接反馈 AI 换策略 */
    fun blockedRepeatReason(session: AgentConversationSession, action: AgentAction): String? {
        if (action.action.equals("finish", ignoreCase = true)) return null
        if (action.action.equals("find_on_page", ignoreCase = true) ||
            action.action.equals("read_tree", ignoreCase = true) ||
            action.action.equals("list_apps", ignoreCase = true)
        ) {
            return null
        }

        val key = actionKey(action)
        val recentFails = session.stepRecords
            .takeLast(8)
            .filter { !it.result.success && actionKey(it.action) == key }

        if (recentFails.size >= 2) {
            return "相同操作「${describe(action)}」已连续失败 ${recentFails.size} 次，禁止再次尝试。请换策略：" +
                "find_on_page 搜索、read_tree 看结构、scroll/swipe 滚动、open_app 切换应用，" +
                "或 finish+waiting_for_user 询问用户。"
        }

        val sameTypeFails = session.stepRecords
            .takeLast(6)
            .count { !it.result.success && it.action.action.equals(action.action, ignoreCase = true) }
        if (sameTypeFails >= 4) {
            return "「${action.action}」已连续失败 $sameTypeFails 次，禁止继续同类操作。必须换完全不同策略。"
        }
        return null
    }

    /**
     * AI 未主动询问时，对敏感操作补一道确认（辅助，非写死业务流程）。
     * 返回 finish 动作表示应询问用户而非直接执行。
     */
    fun sensitiveConfirmOverride(
        session: AgentConversationSession,
        action: AgentAction,
    ): AgentAction? {
        if (action.action.equals("finish", ignoreCase = true)) return null
        if (action.waitingForUser) return null

        val root = session.rootCommand.trim()

        if (action.action.equals("send", ignoreCase = true)) {
            return maybeConfirm(session, SEND_PROMPT, needsBinaryConfirm = true) {
                !session.hasResolvedConfirmTopic(AgentConversationSession.CONFIRM_TOPIC_SEND)
            }
        }

        if (action.action.equals("click", ignoreCase = true)) {
            val target = action.targetText?.trim().orEmpty()
            if (sendKeywords.any { target.contains(it, ignoreCase = true) } &&
                SendIntentDetector.isSendCommand(root)
            ) {
                return maybeConfirm(session, SEND_CLICK_PROMPT, needsBinaryConfirm = true) {
                    !session.hasResolvedConfirmTopic(AgentConversationSession.CONFIRM_TOPIC_SEND)
                }
            }
            if (isCallIntent(root) &&
                callButtonKeywords.any { target.contains(it, ignoreCase = true) }
            ) {
                return maybeConfirm(session, CALL_ROUTE_PROMPT, needsBinaryConfirm = false) {
                    !session.hasResolvedConfirmTopic(AgentConversationSession.CONFIRM_TOPIC_CALL_ROUTE)
                }
            }
        }

        if (isCallIntent(root)) {
            if (action.action.equals("open_app", ignoreCase = true)) {
                val target = action.targetText?.trim().orEmpty().lowercase()
                if (target.contains("电话") || target.contains("拨号") || target.contains("dialer")) {
                    return maybeConfirm(session, CALL_ROUTE_PROMPT, needsBinaryConfirm = false) {
                        !session.hasResolvedConfirmTopic(AgentConversationSession.CONFIRM_TOPIC_CALL_ROUTE)
                    }
                }
            }
        }

        if (LocalCommandParser.isSendToSpecificPerson(root) &&
            action.action.equals("type", ignoreCase = true) &&
            !action.inputText.isNullOrBlank()
        ) {
            return null
        }

        return null
    }

    private inline fun maybeConfirm(
        session: AgentConversationSession,
        prompt: String,
        needsBinaryConfirm: Boolean,
        shouldAsk: () -> Boolean,
    ): AgentAction? {
        if (!shouldAsk()) return null
        if (session.hasAnsweredConfirmPrompt(prompt)) return null
        return confirmFinish(prompt, needsBinaryConfirm)
    }

    private fun confirmFinish(message: String, needsBinaryConfirm: Boolean): AgentAction {
        return AgentAction(
            action = "finish",
            message = message,
            finished = false,
            waitingForUser = true,
            needsBinaryConfirm = needsBinaryConfirm,
        )
    }

    private fun describe(action: AgentAction): String {
        val target = action.targetText?.let { "「$it」" }.orEmpty()
        val input = action.inputText?.let { " 内容=${it.take(20)}" }.orEmpty()
        return "${action.action}$target$input"
    }

    private fun isCallIntent(command: String): Boolean {
        val lower = command.lowercase()
        val hasCallCore = lower.contains("电话") || lower.contains("通话") ||
            lower.contains("呼叫") || lower.contains("拨号") || lower.contains("拨打")
        val hasAction = lower.contains("打") || lower.contains("拨") || lower.contains("呼叫")
        return (hasCallCore && hasAction) || lower.contains("call")
    }
}
