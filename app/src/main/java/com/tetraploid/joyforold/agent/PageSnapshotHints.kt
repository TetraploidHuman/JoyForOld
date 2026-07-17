package com.tetraploid.joyforold.agent

/** 从页面结构推断通用提示，写入页面快览供 Agent 决策。 */
object PageSnapshotHints {
    private val listPageCues = listOf("搜索", "search", "结果", "综合", "筛选", "排序", "列表")
    private val mapSearchCues = listOf("路线", "打车", "团购")
    private val mapNavStartedCues = listOf("开始导航", "退出导航", "继续导航", "正在导航")

    fun linesFor(snapshot: StructuredPageSnapshot): List<String> {
        val hints = mutableListOf<String>()
        val corpus = (snapshot.clickables + snapshot.visibleTexts + snapshot.editables)
            .joinToString(" ")
            .lowercase()
        val pkg = snapshot.packageName.lowercase()
        val isMap = pkg.contains("autonavi") ||
            (pkg.contains("baidu") && pkg.contains("map")) ||
            pkg.contains("tencent.map")

        if (listPageCues.any { corpus.contains(it) } && snapshot.clickables.size >= 4) {
            hints += "页面类型: 疑似列表/搜索页，选中条目通常需要 click"
        }
        if (snapshot.editables.isNotEmpty() && snapshot.clickables.size >= 3) {
            hints += "页面含输入框与多个可点项，输入后常需再 click 确认/选中"
        }
        if (isMap && mapSearchCues.any { corpus.contains(it) } &&
            mapNavStartedCues.none { corpus.contains(it) }
        ) {
            hints += "地图提示: 当前像 POI/搜索列表，尚未开始导航；有「路线」则点「路线」，否则先点与目的地相关的结果项，详情页点底部「导航」（不要点「路线」预览）；勿点「附近x公里」筛选，勿再 navigate_to/finish"
        }
        if (isMap && snapshot.clickables.size >= 3 &&
            mapSearchCues.none { corpus.contains(it) } &&
            mapNavStartedCues.none { corpus.contains(it) } &&
            snapshot.editables.isNotEmpty()
        ) {
            hints += "地图提示: 未见「路线」按钮；请先 click 与目的地相关的结果项，详情页点底部「导航」"
        }
        return hints
    }
}
