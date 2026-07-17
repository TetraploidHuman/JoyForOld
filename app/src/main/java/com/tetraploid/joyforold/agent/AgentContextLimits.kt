package com.tetraploid.joyforold.agent

/**
 * Agent 发给 LLM / 工具反馈的上下文上限。
 * 在可决策前提下控制体积：列表截断 + 工具反馈封顶 + 动态 PageContextMode。
 */
object AgentContextLimits {
    /** 步骤反馈括号内的工具 detail（含 read_tree 输出） */
    const val FEEDBACK_DETAIL_MAX_CHARS = 8_000

    /** read_tree 写入 ActionExecutionResult.detail 的上限 */
    const val READ_TREE_SNIPPET_MAX_CHARS = 8_000

    /** 【当前页面快览】发给云端 LLM 的上限 */
    const val PAGE_COMPACT_SUMMARY_MAX_CHARS = 16_000

    /** UiTreeSerializer 最多序列化节点数 */
    const val UI_TREE_MAX_NODES = 3_000

    /** 构建 StructuredPageSnapshot 时遍历节点上限 */
    const val SNAPSHOT_WALK_MAX_NODES = 4_000

    /** 快览 / diff 中单类列表最多展示条数（0 = 不限制） */
    const val SUMMARY_LIST_CAP = 60

    /** find_on_page 等匹配项最多写入反馈 */
    const val MATCHED_ELEMENTS_CAP = 80

    /** 指纹中纳入的可见文字条数 */
    const val FINGERPRINT_VISIBLE_TEXTS = 80

    /** 单轮规划 JSON 的 max_tokens */
    const val PLAN_MAX_TOKENS = 1_024

    /** logcat 分块输出每块字符数（仅调试） */
    const val DEBUG_LOG_CHUNK_CHARS = 8_000

    /** session 内观察仓环形容量（帧数） */
    const val OBSERVATION_STORE_CAPACITY = 12

    /** query_* 工具写入反馈的上限 */
    const val QUERY_RESULT_MAX_CHARS = 2_000

    /** query_page 默认返回条数 */
    const val QUERY_PAGE_DEFAULT_TOP_K = 24

    fun capList(items: List<String>, limit: Int): List<String> =
        if (limit <= 0) items else items.take(limit)

    /** 带总数与省略提示的列表文本，供快览 / diff 复用 */
    fun formatCappedJoined(items: List<String>, limit: Int): String {
        val shown = capList(items, limit)
        val body = shown.joinToString(" | ")
        return if (limit > 0 && items.size > limit) {
            "$body …另有${items.size - limit}项省略"
        } else {
            body
        }
    }
}
