package com.tetraploid.joyforold.agent.actionsets

import com.tetraploid.joyforold.agent.actionsets.dsl.ParamSource
import com.tetraploid.joyforold.agent.actionsets.dsl.actionScript
import com.tetraploid.joyforold.agent.actionsets.dsl.param

/**
 * 淘宝搜索相关动作组（淘宝专用）。
 *
 * **设计目的**：固定「打开 → 进搜索 → 输入 → 点搜索」在本地执行，避免主规划轮
 * 反复上传整棵淘宝 UI 树（动辄上万 token）。需要从结果列表挑商品时，只把列表候选
 * 切片交给窄域 askLlm 选型，而不是把全树丢给主模型。
 *
 * 页面依据 UI 树日志：搜索门有「历史搜索」/ 可编辑框 /「搜索」按钮；
 * 结果页商品多为长 contentDescription（可 click）。本动作组不下单、不加购。
 */
object TaobaoSearchActionSet {
    const val TAOBAO_APP = "淘宝"
    /** 搜索门页标志（首页通常没有）。 */
    const val SEARCH_DOOR_MARKER = "历史搜索"
    /** 首页顶部搜索栏常见无障碍文案。 */
    const val HOME_SEARCH_ENTRY = "搜索栏"
    const val SEARCH_BUTTON = "搜索"

    /**
     * 只搜索：打开淘宝 → 进入搜索门 → 输入关键词 → 点「搜索」→ 结束（留在结果页）。
     * 参数：input_text = 搜索关键词。
     */
    val searchOnly = actionScript("taobao_search") {
        uiLabel { p ->
            val q = p["query"]
            if (q.isNotBlank()) "动作组：淘宝搜索「$q」" else "动作组：淘宝搜索"
        }
        require("query", from = ParamSource.INPUT_TEXT)

        openApp(TAOBAO_APP).wait()
        find(SEARCH_DOOR_MARKER) {
            ok { wait() }
            miss { click(HOME_SEARCH_ENTRY).wait() }
        }
        type(param("query")).wait()
        click(SEARCH_BUTTON).wait()
        finish { p -> "已在淘宝搜索：${p["query"]}" }
    }

    /**
     * 搜索并打开商品：固定搜到结果页后，只把**结果列表候选**发给窄域 askLlm 选型，再点击进详情。
     * 参数：input_text = 搜索关键词（兼商品意图）。
     */
    val searchAndOpen = actionScript("taobao_search_open") {
        uiLabel { p ->
            val q = p["query"]
            if (q.isNotBlank()) "动作组：淘宝搜并打开「$q」" else "动作组：淘宝搜并打开商品"
        }
        require("query", from = ParamSource.INPUT_TEXT)
        optional("product", default = "")

        openApp(TAOBAO_APP).wait()
        find(SEARCH_DOOR_MARKER) {
            ok { wait() }
            miss { click(HOME_SEARCH_ENTRY).wait() }
        }
        type(param("query")).wait()
        click(SEARCH_BUTTON).wait()

        // 只采列表等会变片段 → 窄域选型（不传整棵 UI 树）
        captureTexts(into = "candidates")
        askLlm(
            writeTo = listOf("product"),
            system = """
                你在帮老人从淘宝**搜索结果列表**里选一件商品，供后续点击。
                输入只有：用户意图 + 当前页采到的列表候选（不是完整界面树）。
                任务：选出最符合用户意图的一条候选文案，供无障碍按文案点击。
                规则：
                1. product 必须尽量完整拷贝候选原句（便于点击匹配），不要自己改写拼凑。
                2. 优先选真正的商品卡片，而不是筛选栏、店铺入口、广告杂项。
                3. 若多条都合适，选与用户意图重合度最高的一条；难以判断时选列表靠前的商品卡。
                4. 候选为空时 product 填用户原话。
                严格返回 JSON：{"product":"..."}。
            """.trimIndent(),
            user = { p ->
                "用户想找：${p["query"]}\n结果列表候选（| 分隔）：${p["candidates"].ifBlank { "（空）" }}"
            },
        )

        // 列表文案对不上时用关键词再点一次（scroll/重采留给后续增强）
        find(param("product")) {
            ok { click(param("product")).wait() }
            miss { click(param("query")).wait() }
        }

        finish { p ->
            val title = p["product"].ifBlank { p["query"] }
            "已在淘宝打开商品：$title"
        }
    }
}
