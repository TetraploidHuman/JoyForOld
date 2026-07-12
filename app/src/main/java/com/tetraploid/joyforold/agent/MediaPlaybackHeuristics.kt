package com.tetraploid.joyforold.agent

/**
 * 视频/音频播放类任务：进入详情页且标题匹配时，通常已自动播放，应 finish 而非继续点「播放按钮」。
 */
object MediaPlaybackHeuristics {
    private val playIntentPattern = Regex("播放|放一下|听(?:一|点|首)?|watch|play", RegexOption.IGNORE_CASE)
    private val bilibiliPackages = setOf("tv.danmaku.bili", "com.bilibili.app.in", "com.bilibili")
    private val abstractPlaybackTargets = setOf(
        "视频播放区域", "播放区域", "播放按钮", "开始播放", "暂停播放", "暂停", "play", "pause",
        "播放器", "视频区域", "播放控件",
    )
    private val videoDetailSignals = listOf(
        "条弹幕", "万播放", "正在看", "个点赞", "个投币", "个收藏", "个分享", "关注up主",
    )

    fun rootCommandImpliesPlayback(rootCommand: String): Boolean =
        playIntentPattern.containsMatchIn(rootCommand.trim())

    fun isAbstractPlaybackTarget(target: String?): Boolean {
        val t = target?.trim().orEmpty()
        if (t.isBlank()) return false
        if (abstractPlaybackTargets.any { t.equals(it, ignoreCase = true) }) return true
        return t.contains("播放按钮", ignoreCase = true) ||
            t.contains("视频播放", ignoreCase = true) ||
            (t.contains("播放", ignoreCase = true) && t.length <= 6)
    }

    fun lastTypedSearchQuery(session: AgentConversationSession): String? {
        return session.stepRecords.lastOrNull { step ->
            step.action.action.equals("type", ignoreCase = true) &&
                step.result.success &&
                step.action.inputText.orEmpty().trim().length >= 2
        }?.action?.inputText?.trim()
    }

    fun contentSearchTerms(session: AgentConversationSession, rootCommand: String): List<String> {
        val terms = linkedSetOf<String>()
        lastTypedSearchQuery(session)?.let { typed ->
            terms += typed
            if (typed.length >= 3) {
                for (len in 2..minOf(typed.length, 8)) {
                    terms += typed.takeLast(len)
                }
            }
        }
        playIntentPattern.find(rootCommand)?.let { match ->
            val after = rootCommand.substring(match.range.last + 1)
                .trim()
                .trim('，', ',', '。', '.', ' ', '、')
            if (after.length >= 2) terms += after
        }
        return terms.filter { it.length >= 2 }
    }

    fun isOnVideoDetailPage(
        snapshot: StructuredPageSnapshot?,
        session: AgentConversationSession,
        rootCommand: String,
    ): Boolean {
        if (snapshot == null) return false
        val terms = contentSearchTerms(session, rootCommand)
        if (terms.isEmpty()) return false
        val corpus = pageCorpus(snapshot)
        if (!terms.any { corpus.contains(it.lowercase()) }) return false
        if (!isLikelyVideoApp(snapshot.packageName)) return false
        val onSearchResults = corpus.contains("综合") &&
            (corpus.contains("番剧") || corpus.contains("search_result"))
        if (onSearchResults) return false
        return videoDetailSignals.any { corpus.contains(it) }
    }

    @Deprecated("Use isOnVideoDetailPage(snapshot, session, rootCommand)")
    fun isOnVideoDetailPage(snapshot: StructuredPageSnapshot?, query: String): Boolean {
        if (snapshot == null || query.length < 2) return false
        val corpus = pageCorpus(snapshot)
        if (!corpus.contains(query.lowercase())) return false
        if (!isLikelyVideoApp(snapshot.packageName)) return false
        val onSearchResults = corpus.contains("综合") &&
            (corpus.contains("番剧") || corpus.contains("search_result"))
        if (onSearchResults) return false
        return videoDetailSignals.any { corpus.contains(it) }
    }

    fun shouldCompletePlayback(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): Boolean {
        if (!rootCommandImpliesPlayback(rootCommand)) return false
        if (!isOnVideoDetailPage(snapshot, session, rootCommand)) return false
        return hasSuccessfulVideoResultClick(session)
    }

    fun buildFinishAction(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): AgentAction {
        val terms = contentSearchTerms(session, rootCommand)
        val title = findMatchingTitle(snapshot, terms) ?: terms.firstOrNull().orEmpty()
        val message = if (title.isNotBlank()) {
            "已为您播放：$title"
        } else {
            "已进入视频页面，正在播放"
        }
        return AgentAction(
            action = "finish",
            message = message,
            finished = true,
            waitingForUser = false,
        )
    }

    /**
     * 已在详情页时，将无效的「点播放区」或重复的 read_tree 替换为 finish。
     */
    fun interceptStuckPlaybackAction(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
        action: AgentAction,
    ): AgentAction? {
        if (!shouldCompletePlayback(session, snapshot, rootCommand)) return null
        if (action.action.equals("finish", ignoreCase = true)) return null
        return when {
            action.action.equals("click", ignoreCase = true) &&
                isAbstractPlaybackTarget(action.targetText) ->
                buildFinishAction(session, snapshot, rootCommand)
            action.action.equals("read_tree", ignoreCase = true) &&
                isStuckInPlaybackLoop(session) ->
                buildFinishAction(session, snapshot, rootCommand)
            else -> null
        }
    }

    fun plannerHint(
        session: AgentConversationSession,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
    ): String? {
        if (!shouldCompletePlayback(session, snapshot, rootCommand)) return null
        val query = lastTypedSearchQuery(session).orEmpty()
        return "【系统判定】当前已在视频详情页，页面可见「$query」。进入详情页后通常会自动播放，" +
            "无障碍树中往往没有「播放按钮」。请直接 finish 向用户汇报，勿再 read_tree/click 播放区域。"
    }

    private fun isStuckInPlaybackLoop(session: AgentConversationSession): Boolean {
        val recent = session.stepRecords.takeLast(6)
        val readTrees = recent.count {
            it.action.action.equals("read_tree", ignoreCase = true) && it.result.success
        }
        val failedAbstractClicks = recent.count { step ->
            step.action.action.equals("click", ignoreCase = true) &&
                !step.result.success &&
                isAbstractPlaybackTarget(step.action.targetText)
        }
        return readTrees >= 1 && failedAbstractClicks >= 1
    }

    private fun hasSuccessfulVideoResultClick(session: AgentConversationSession): Boolean {
        return session.stepRecords.any { step ->
            step.action.action.equals("click", ignoreCase = true) &&
                step.result.success &&
                (step.action.targetText?.length ?: 0) >= 8
        }
    }

    private fun findMatchingTitle(snapshot: StructuredPageSnapshot?, terms: List<String>): String? {
        if (snapshot == null || terms.isEmpty()) return null
        val candidates = snapshot.visibleTexts + snapshot.clickables
        for (term in terms.sortedByDescending { it.length }) {
            val q = term.lowercase()
            candidates.firstOrNull { it.lowercase().contains(q) && it.length >= q.length }?.let { return it }
        }
        return null
    }

    private fun isLikelyVideoApp(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return bilibiliPackages.any { pkg.contains(it) } ||
            pkg.contains("youtube") ||
            pkg.contains("youku") ||
            pkg.contains("iqiyi")
    }

    private fun pageCorpus(snapshot: StructuredPageSnapshot): String {
        return (snapshot.clickables + snapshot.visibleTexts + snapshot.editables)
            .joinToString(" ")
            .lowercase()
    }
}
