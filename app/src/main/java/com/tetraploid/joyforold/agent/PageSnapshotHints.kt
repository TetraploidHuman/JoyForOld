package com.tetraploid.joyforold.agent

/** 从页面结构推断通用提示，写入页面快览供 Agent 决策。 */
object PageSnapshotHints {
    private val listPageCues = listOf("搜索", "search", "结果", "综合", "筛选", "排序", "列表")

    fun linesFor(snapshot: StructuredPageSnapshot): List<String> {
        val hints = mutableListOf<String>()
        val corpus = (snapshot.clickables + snapshot.visibleTexts + snapshot.editables)
            .joinToString(" ")
            .lowercase()

        if (listPageCues.any { corpus.contains(it) } && snapshot.clickables.size >= 4) {
            hints += "页面类型: 疑似列表/搜索页，选中条目通常需要 click"
        }
        if (snapshot.editables.isNotEmpty() && snapshot.clickables.size >= 3) {
            hints += "页面含输入框与多个可点项，输入后常需再 click 确认/选中"
        }
        return hints
    }
}
