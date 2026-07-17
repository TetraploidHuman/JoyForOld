package com.tetraploid.joyforold.agent

/**
 * Session 级页面观察仓：保留近期快照/diff，完整树按需挂载。
 * 纯内存、不落盘；供规划短提示与 [AgentObservationQueries] 检索。
 *
 * 扩展：新字段放 [ObservationFrame]；新检索算子加方法，再在 Queries 中暴露工具。
 */
class AgentObservationStore(
    private val capacity: Int = AgentContextLimits.OBSERVATION_STORE_CAPACITY,
) {
    data class ObservationFrame(
        val step: Int,
        val packageName: String,
        val fingerprint: String,
        val snapshot: StructuredPageSnapshot,
        val diff: String,
        val treeSnippet: String? = null,
    )

    private val frames = ArrayDeque<ObservationFrame>()

    val size: Int get() = frames.size

    fun clear() {
        frames.clear()
    }

    fun record(
        step: Int,
        snapshot: StructuredPageSnapshot,
        diff: String,
        treeSnippet: String? = null,
    ): ObservationFrame {
        val inheritedTree = frames.lastOrNull()
            ?.takeIf { it.fingerprint == snapshot.fingerprint }
            ?.treeSnippet
        val frame = ObservationFrame(
            step = step,
            packageName = snapshot.packageName,
            fingerprint = snapshot.fingerprint,
            snapshot = snapshot,
            diff = diff,
            treeSnippet = treeSnippet ?: inheritedTree,
        )
        frames.addLast(frame)
        while (frames.size > capacity.coerceAtLeast(1)) {
            frames.removeFirst()
        }
        return frame
    }

    fun latest(): ObservationFrame? = frames.lastOrNull()

    fun get(step: Int): ObservationFrame? = frames.lastOrNull { it.step == step }

    fun attachTree(step: Int? = null, tree: String): Boolean {
        val cleaned = tree.trim()
        if (cleaned.isBlank()) return false
        val targetStep = step ?: latest()?.step ?: return false
        val list = frames.toMutableList()
        val idx = list.indexOfLast { it.step == targetStep }
        if (idx < 0) return false
        list[idx] = list[idx].copy(treeSnippet = cleaned)
        frames.clear()
        list.forEach { frames.addLast(it) }
        return true
    }

    fun attachTreeToLatest(tree: String): Boolean =
        attachTree(step = latest()?.step, tree = tree)

    fun resolveStep(stepHint: Int?): ObservationFrame? =
        when (stepHint) {
            null -> latest()
            else -> get(stepHint) ?: latest()
        }

    fun formatPromptHint(): String {
        if (frames.isEmpty()) return ""
        val steps = frames.map { it.step }.joinToString(",")
        val latest = latest() ?: return ""
        val treeTag = if (latest.treeSnippet.isNullOrBlank()) "无树缓存" else "有树缓存"
        return "【本地观察仓】已缓存 ${frames.size} 帧（steps=$steps；最新 step=${latest.step} $treeTag），" +
            "可用 query_page / query_diff / query_tree 按需查询，勿要求每轮全量树。"
    }

    fun queryPage(
        stepHint: Int? = null,
        keyword: String? = null,
        topK: Int = AgentContextLimits.QUERY_PAGE_DEFAULT_TOP_K,
    ): String {
        val frame = resolveStep(stepHint)
            ?: return "观察仓为空，请先观察页面或 wait 后再 query_page。"
        val snap = frame.snapshot
        val kw = keyword?.trim().orEmpty()
        fun match(items: List<String>): List<String> =
            if (kw.isBlank()) items else items.filter { it.contains(kw, ignoreCase = true) }

        val clickables = match(snap.clickables)
        val editables = match(snap.editables)
        val texts = match(snap.visibleTexts)
        val sends = match(snap.sendButtons)
        val limit = topK.coerceAtLeast(1)

        return truncateResult(
            buildString {
                appendLine("step=${frame.step} pkg=${frame.packageName} fp=${frame.fingerprint.take(12)}")
                if (kw.isNotBlank()) appendLine("关键词=$kw")
                appendLine("可点击(${clickables.size}): ${joinCapped(clickables, limit)}")
                appendLine("可输入(${editables.size}): ${joinCapped(editables, limit)}")
                if (sends.isNotEmpty()) {
                    appendLine("发送相关(${sends.size}): ${joinCapped(sends, limit)}")
                }
                appendLine("可见文字(${texts.size}): ${joinCapped(texts, limit)}")
            },
        )
    }

    fun queryDiff(stepHint: Int? = null): String {
        val frame = resolveStep(stepHint)
            ?: return "观察仓为空，请先观察页面后再 query_diff。"
        return truncateResult(
            buildString {
                appendLine("step=${frame.step} pkg=${frame.packageName}")
                append(frame.diff.ifBlank { "（无 diff）" })
            },
        )
    }

    /** @return null 表示无帧或无树缓存，调用方应区分空仓 / 现采 */
    fun queryTreeCached(stepHint: Int? = null, keyword: String? = null): String? {
        val frame = resolveStep(stepHint) ?: return null
        val tree = frame.treeSnippet?.takeIf { it.isNotBlank() } ?: return null
        return truncateResult(filterTree(tree, keyword, frame.step))
    }

    fun formatFreshTree(tree: String, step: Int, keyword: String?): String =
        truncateResult(filterTree(tree, keyword, step))

    private fun filterTree(tree: String, keyword: String?, step: Int): String {
        val kw = keyword?.trim().orEmpty()
        if (kw.isBlank()) {
            return buildString {
                appendLine("step=$step（结构树节选）")
                append(tree)
            }
        }
        val matched = tree.lineSequence()
            .filter { it.contains(kw, ignoreCase = true) }
            .take(80)
            .joinToString("\n")
        return buildString {
            appendLine("step=$step 关键词=$kw")
            append(matched.ifBlank { "（树中未匹配到该关键词）" })
        }
    }

    private fun joinCapped(items: List<String>, limit: Int): String =
        AgentContextLimits.formatCappedJoined(items, limit)

    private fun truncateResult(raw: String): String {
        val max = AgentContextLimits.QUERY_RESULT_MAX_CHARS
        val text = raw.trimEnd()
        if (text.length <= max) return text
        return text.take(max) + "\n...（查询结果已截断，共 ${text.length} 字）"
    }
}
