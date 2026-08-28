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
    const val SEND_PROMPT = "即将发送消息。请确认：要说「发送」还是「取消」？"
    const val SEND_CLICK_PROMPT = "即将点击发送。请确认：要说「发送」还是「取消」？"

    fun isSendConfirmPrompt(prompt: String): Boolean {
        val text = prompt.trim()
        return text == SEND_PROMPT ||
            text == SEND_CLICK_PROMPT ||
            (text.contains("发送") && (text.contains("确认") || text.contains("取消")))
    }

    fun actionKey(action: AgentAction): String {
        return buildString {
            append(action.action.lowercase())
            action.targetText?.trim()?.lowercase()?.let { append("|").append(it) }
            action.inputText?.trim()?.take(40)?.lowercase()?.let { append("|").append(it) }
        }
    }

    /** 相同操作已成功且页面指纹未变 → 不再执行（DroidLM/Mantis 思路：重复 + 无进展） */
    fun blockedRepeatReason(
        session: AgentConversationSession,
        action: AgentAction,
        pageUnchangedSinceLastStep: Boolean = false,
        a11yUnavailable: Boolean = false,
    ): String? {
        if (action.action.equals("finish", ignoreCase = true)) return null
        if (action.action.equals("list_apps", ignoreCase = true)) {
            return null
        }

        if (action.action.equals("read_tree", ignoreCase = true) && pageUnchangedSinceLastStep) {
            val recentReadTrees = session.stepRecords.takeLast(4).count {
                it.action.action.equals("read_tree", ignoreCase = true) && it.result.success
            }
            if (recentReadTrees >= 2) {
                return "页面未变化，禁止重复 read_tree。若已在视频详情页且标题匹配，请直接 finish。"
            }
        }

        if (action.action.equals(AgentObservationQueries.ACTION_QUERY_TREE, ignoreCase = true) &&
            pageUnchangedSinceLastStep
        ) {
            val recent = session.stepRecords.takeLast(4).count {
                it.action.action.equals(AgentObservationQueries.ACTION_QUERY_TREE, ignoreCase = true) &&
                    it.result.success &&
                    actionKey(it.action) == actionKey(action)
            }
            if (recent >= 2) {
                return "页面未变化，禁止重复同条件 query_tree。请换关键词，或用 query_page / click 推进。"
            }
        }

        val key = actionKey(action)
        val recentFails = session.stepRecords
            .takeLast(8)
            .filter { !it.result.success && actionKey(it.action) == key }

        if (recentFails.size >= 2) {
            return "相同操作「${describe(action)}」已连续失败 ${recentFails.size} 次，禁止再次尝试。" +
                replanHint(a11yUnavailable, action)
        }

        if (pageUnchangedSinceLastStep) {
            val priorSuccess = session.stepRecords.takeLast(8).any {
                it.result.success && actionKey(it.action) == key
            }
            if (priorSuccess) {
                return "相同操作「${describe(action)}」已成功执行过且【页面变化】显示无进展，禁止重复。" +
                    replanHint(a11yUnavailable, action)
            }
        }

        val recentSameSuccess = session.stepRecords.takeLast(6).count {
            it.result.success && actionKey(it.action) == key
        }
        if (recentSameSuccess >= 2 &&
            action.action.equals("click", ignoreCase = true)
        ) {
            return "相同点击「${describe(action)}」已连续成功 $recentSameSuccess 次仍未推进任务，禁止再次重复。" +
                if (a11yUnavailable) {
                    "请换 tap 坐标或 scroll。"
                } else {
                    "请 read_tree 换目标或 scroll。"
                }
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

        if (action.action.equals("tap", ignoreCase = true) &&
            SendIntentDetector.isSendCommand(root) &&
            recentTypedMessageInSendFlow(session)
        ) {
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

    fun pageDiffIndicatesNoChange(pageDiff: String): Boolean =
        VisionScreenChange.indicatesNoProgress(pageDiff)

    /** 仅依据无障碍 diff 文案（不含视觉截图信号） */
    fun pageDiffIndicatesNoChangeA11yOnly(pageDiff: String): Boolean {
        if (pageDiff.isBlank()) return false
        return pageDiff.contains("无明显变化") ||
            pageDiff.contains("指纹未变") ||
            pageDiff.contains("页面指纹未变")
    }

    /** 无障碍树不可用时的 action 拦截（视觉模式） */
    fun blockedInVisionMode(action: AgentAction): String? = when (action.action.lowercase()) {
        "read_tree" ->
            "当前应用无障碍树不可用，请勿 read_tree；请根据截图用 tap 坐标操作。"
        "click" ->
            "当前应用无障碍树不可用，请用 tap（归一化坐标）代替 click。"
        "find_on_page" ->
            "当前应用无障碍树不可用，find_on_page 无效；请根据截图规划 tap。"
        AgentObservationQueries.ACTION_QUERY_PAGE,
        AgentObservationQueries.ACTION_QUERY_DIFF,
        AgentObservationQueries.ACTION_QUERY_TREE,
        ->
            "当前应用无障碍树不可用，观察仓查询无效；请根据截图用 tap。"
        else -> null
    }

    /**
     * 无障碍树可用时禁止视觉坐标点击。
     * tap 坐标由模型估测，易点偏；有可点击标签时应 click。
     */
    fun blockedWhenA11yAvailable(action: AgentAction): String? = when (action.action.lowercase()) {
        "tap" ->
            "当前无障碍树可用，禁止 tap 坐标点击。请用 click，target_text 填页面快览中的可点击文案（如联系人名）。"
        else -> null
    }

    /**
     * 给某人发消息时：会话列表已有联系人行，禁止点「搜索 / 搜索小程序」。
     */
    fun blockedWrongImSearch(
        session: AgentConversationSession,
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
    ): String? {
        if (!action.action.equals("click", ignoreCase = true)) return null
        val target = action.targetText?.trim().orEmpty()
        if (target.isEmpty() || !SearchTaskHeuristics.isSearchLikeLabel(target)) return null
        val contact = SearchTaskHeuristics.extractImContact(session.rootCommand) ?: return null
        val snap = snapshot ?: return null
        val hit = SearchTaskHeuristics.findVisibleContactClickable(snap, contact) ?: return null
        return "发消息给「$contact」时，列表已有「$hit」，禁止点搜索。请直接 click「$hit」。"
    }

    private fun replanHint(a11yUnavailable: Boolean, action: AgentAction): String =
        if (a11yUnavailable || action.action.equals("tap", ignoreCase = true)) {
            "请根据截图换完全不同 tap 坐标，或 finish+waiting_for_user 询问用户。"
        } else {
            "请根据页面快览/query_page/read_tree 换完全不同策略，或 finish+waiting_for_user 询问用户。"
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

    /** 发消息流程中刚输入正文后，视觉 tap 多半是在点发送。 */
    private fun recentTypedMessageInSendFlow(session: AgentConversationSession): Boolean {
        return session.stepRecords.takeLast(4).any { record ->
            record.action.action.equals("type", ignoreCase = true) &&
                record.result.success &&
                !record.action.inputText.isNullOrBlank()
        }
    }
}
