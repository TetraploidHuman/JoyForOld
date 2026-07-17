package com.tetraploid.joyforold.agent

/**
 * 观察仓查询工具的集中注册与分发。
 * 新增 query_*：在此加 action 常量 + [execute] 分支，并登记到 [AgentToolRegistry]。
 */
object AgentObservationQueries {
    const val ACTION_QUERY_PAGE = "query_page"
    const val ACTION_QUERY_DIFF = "query_diff"
    const val ACTION_QUERY_TREE = "query_tree"

    val allActionNames: List<String> = listOf(
        ACTION_QUERY_PAGE,
        ACTION_QUERY_DIFF,
        ACTION_QUERY_TREE,
    )

    fun isObservationQuery(action: String): Boolean =
        action.lowercase() in allActionNames

    fun descriptionsForPrompt(visionMode: Boolean): String {
        if (visionMode) {
            return """
                - query_page / query_diff / query_tree: 当前不可用（无障碍树为空）；请根据截图用 tap
            """.trimIndent()
        }
        return """
            - query_page: 查询本地观察仓摘要；target_text=关键词（可空=TopK）；input_text 可选填 step 数字
            - query_diff: 查询某步相对上一步的页面变化；input_text 可选填 step
            - query_tree: 查询缓存结构树（可按 target_text 过滤）；无缓存时现采并写入观察仓
        """.trimIndent()
    }

    /**
     * @param liveTreeFetcher 无树缓存时现采（通常执行 read_tree）；可为 null（仅本地）
     */
    suspend fun execute(
        action: AgentAction,
        store: AgentObservationStore?,
        liveTreeFetcher: (suspend () -> ActionExecutionResult)? = null,
    ): ActionExecutionResult {
        if (store == null) {
            return ActionExecutionResult(
                success = false,
                summary = "观察仓不可用",
                detail = "当前执行路径未绑定 ObservationStore，无法 query_*",
                suggestions = listOf("用 find_on_page 或 read_tree"),
            )
        }
        return when (action.action.lowercase()) {
            ACTION_QUERY_PAGE -> executeQueryPage(action, store)
            ACTION_QUERY_DIFF -> executeQueryDiff(action, store)
            ACTION_QUERY_TREE -> executeQueryTree(action, store, liveTreeFetcher)
            else -> ActionExecutionResult(false, "未知观察查询：${action.action}")
        }
    }

    /** read_tree 成功后挂到最新帧，便于后续 query_tree 复查。 */
    fun rememberReadTree(store: AgentObservationStore?, result: ActionExecutionResult) {
        if (store == null || !result.success) return
        val tree = result.detail.trim()
        if (tree.isBlank()) return
        store.attachTreeToLatest(tree)
    }

    private fun executeQueryPage(
        action: AgentAction,
        store: AgentObservationStore,
    ): ActionExecutionResult {
        val body = store.queryPage(
            stepHint = parseStepHint(action.inputText),
            keyword = action.targetText,
        )
        return ActionExecutionResult(
            success = !body.startsWith("观察仓为空"),
            summary = "已查询本地页面摘要",
            detail = body,
        )
    }

    private fun executeQueryDiff(
        action: AgentAction,
        store: AgentObservationStore,
    ): ActionExecutionResult {
        val body = store.queryDiff(stepHint = parseStepHint(action.inputText))
        return ActionExecutionResult(
            success = !body.startsWith("观察仓为空"),
            summary = "已查询本地页面变化",
            detail = body,
        )
    }

    private suspend fun executeQueryTree(
        action: AgentAction,
        store: AgentObservationStore,
        liveTreeFetcher: (suspend () -> ActionExecutionResult)?,
    ): ActionExecutionResult {
        if (store.latest() == null) {
            return ActionExecutionResult(
                success = false,
                summary = "观察仓为空",
                detail = "请先观察页面或 wait 后再 query_tree",
                suggestions = listOf("wait", "read_tree"),
            )
        }
        val stepHint = parseStepHint(action.inputText)
        val keyword = action.targetText
        store.queryTreeCached(stepHint, keyword)?.let { cached ->
            return ActionExecutionResult(
                success = true,
                summary = "已查询本地结构树缓存",
                detail = cached,
            )
        }

        val live = liveTreeFetcher?.invoke()
            ?: return ActionExecutionResult(
                success = false,
                summary = "无树缓存且无法现采",
                detail = "请先 read_tree，或确保无障碍可用后再 query_tree",
                suggestions = listOf("read_tree", "wait"),
            )
        if (!live.success) {
            return ActionExecutionResult(
                success = false,
                summary = "现采结构树失败",
                detail = live.detail.ifBlank { live.summary },
                suggestions = live.suggestions,
            )
        }
        val tree = live.detail.trim()
        if (tree.isBlank()) {
            return ActionExecutionResult(false, "现采结构树为空", detail = live.summary)
        }
        store.attachTree(step = store.resolveStep(stepHint)?.step, tree = tree)
        val step = store.resolveStep(stepHint)?.step ?: store.latest()?.step ?: 0
        return ActionExecutionResult(
            success = true,
            summary = "已现采并缓存结构树",
            detail = store.formatFreshTree(tree, step, keyword),
        )
    }

    private fun parseStepHint(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.toIntOrNull()
    }
}
