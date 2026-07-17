package com.tetraploid.joyforold.agent

/**
 * finish 的**客观**校验：不解析自然语言意图，只拦截明显与事实不符的结束。
 * 「任务是否完成」由 Agent（DeepSeek）结合用户指令与页面状态自行判断。
 */
object AgentFinishGuard {
    private val accomplishmentPhrases = listOf(
        "已开始", "已经开始", "正在", "已完成", "完成了", "成功了", "好了",
        "已打开", "已发送", "已播放", "已找到", "已进入", "已切换", "已为您",
    )

    private val navigationCommandCues = listOf(
        "导航", "带我去", "带我到", "带我前往", "怎么走", "路线到", "去最近", "去附近",
    )

    private val navigationStartedCues = listOf(
        "开始导航", "退出导航", "继续导航", "正在导航", "导航中", "结束导航",
        "模拟导航", "全览",
    )

    private val mapSearchListCues = listOf("路线", "打车", "团购")

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
            ?: prematureNavigationFinish(session, snapshot, rootCommand, message)
            ?: finishContradictsPage(session, snapshot, message)
    }

    /** 导航类指令：仍停在地图搜索/POI 列表时禁止 finish（搜到店名 ≠ 已开始导航）。 */
    private fun prematureNavigationFinish(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
        message: String,
    ): String? {
        val cmd = rootCommand.trim()
        if (cmd.isBlank()) return null
        if (navigationCommandCues.none { cmd.contains(it) } &&
            !Regex("""去(?:最近的|附近的).+""").containsMatchIn(cmd)
        ) {
            return null
        }
        // 回家类由 navigate_home 深链完成，不按 UI 态卡
        if (cmd.contains("回家") || cmd.contains("家里")) return null

        // 回家 / 目的地深链已调起地图，可直接结束（勿再要求点「开始导航」）
        val usedDeepLink = session.stepRecords.any { step ->
            if (!step.result.success) return@any false
            val a = step.action.action.lowercase()
            a == "navigate_home" || a == "navigate_to"
        }
        if (usedDeepLink) return null

        val snap = snapshot ?: return null
        if (!isMapPackage(snap.packageName)) return null

        val corpus = pageCorpus(snap)
        if (navigationStartedCues.any { corpus.contains(it) }) return null

        val onSearchList = mapSearchListCues.any { corpus.contains(it) } ||
            snap.editables.isNotEmpty()
        if (!onSearchList && !claimsAccomplishment(message)) return null

        return "系统判定：导航任务尚未进入导航态（未见「开始导航」等）。" +
            "搜索结果/POI 列表不算完成；请用 navigate_to 深链，或动作组 map_navigate / 点击「开始导航」后再 finish。"
    }

    private fun isMapPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("autonavi") ||
            (pkg.contains("baidu") && pkg.contains("map")) ||
            pkg.contains("tencent.map") ||
            pkg.contains("google.android.apps.maps")
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
        "navigate_to", "navigate_home",
    )
}
