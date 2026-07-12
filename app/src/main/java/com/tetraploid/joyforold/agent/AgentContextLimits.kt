package com.tetraploid.joyforold.agent

/**
 * Agent 发给 LLM / 工具反馈的上下文上限。
 * 效果优先：尽量传完整页面与结构树，仅在极端长度时截断。
 */
object AgentContextLimits {
    /** 步骤反馈括号内的工具 detail（含 read_tree 输出） */
    const val FEEDBACK_DETAIL_MAX_CHARS = 48_000

    /** read_tree 写入 ActionExecutionResult.detail 的上限 */
    const val READ_TREE_SNIPPET_MAX_CHARS = 48_000

    /** 【当前页面快览】发给云端 LLM 的上限 */
    const val PAGE_COMPACT_SUMMARY_MAX_CHARS = 32_000

    /** UiTreeSerializer 最多序列化节点数 */
    const val UI_TREE_MAX_NODES = 3_000

    /** 构建 StructuredPageSnapshot 时遍历节点上限 */
    const val SNAPSHOT_WALK_MAX_NODES = 4_000

    /** 快览 / diff 中单类列表最多展示条数（0 = 不限制） */
    const val SUMMARY_LIST_CAP = 0

    /** find_on_page 等匹配项最多写入反馈 */
    const val MATCHED_ELEMENTS_CAP = 80

    /** 指纹中纳入的可见文字条数 */
    const val FINGERPRINT_VISIBLE_TEXTS = 80

    /** 单轮规划 JSON 的 max_tokens */
    const val PLAN_MAX_TOKENS = 1_024

    /** logcat 分块输出每块字符数（仅调试） */
    const val DEBUG_LOG_CHUNK_CHARS = 8_000

    fun capList(items: List<String>, limit: Int): List<String> =
        if (limit <= 0) items else items.take(limit)
}
