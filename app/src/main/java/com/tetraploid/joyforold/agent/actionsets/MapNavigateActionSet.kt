package com.tetraploid.joyforold.agent.actionsets

import com.tetraploid.joyforold.agent.actionsets.dsl.ParamSource
import com.tetraploid.joyforold.agent.actionsets.dsl.actionScript
import com.tetraploid.joyforold.agent.actionsets.dsl.param

/**
 * 地图导航动作组：目的地由用户话术动态传入（`query`），不写死具体地点。
 *
 * 流程与地点无关：
 * 1. 深链打开地图周边搜索（关键词=query）
 * 2. 若列表项自带「路线」→ 点最近的「路线」
 * 3. 否则从当前页候选里选出与 query 最相关的一条（captureTexts + 窄域 askLlm）再点进详情
 * 4. 再点「开始导航」或详情底栏「导航」
 *
 * 「路线 / 导航 / 开始导航」是地图 App 的通用控件文案，不是某个品牌专用。
 */
object MapNavigateActionSet {
    const val ID = "map_navigate"
    const val ROUTE_BUTTON = "路线"
    const val START_NAV = "开始导航"
    const val NAV_BUTTON = "导航"

    val definition = actionScript(ID) {
        uiLabel { p ->
            val q = p["query"]
            if (q.isNotBlank()) "动作组：导航前往「$q」" else "动作组：地图导航"
        }
        require("query", from = ParamSource.INPUT_TEXT)
        optional("poi", default = "")

        navigateTo(param("query")).wait()
        wait()

        // 先采周边结果候选（肯德基/公园/医院…都走同一套）
        captureTexts(into = "candidates")
        askLlm(
            writeTo = listOf("poi"),
            system = """
                你在帮老人从地图「周边/搜索结果」里选一个要去的地点。
                输入只有：用户目的地关键词 + 当前页采到的可点/可见候选（| 分隔），不是完整界面树。
                任务：选出最符合用户意图、且通常也是最近的一条结果，供后续点击。
                规则：
                1. poi 必须尽量完整拷贝候选原句（便于点击匹配），不要改写拼凑。
                2. 优先选真正的地点/门店卡片（常含括号分店名或地址），不要选「搜索」「返回」「筛选」「附近x公里」「打车」「团购」「语音」等控件。
                3. 多条都合适时，选列表更靠前的一条（周边结果通常已按距离排序）。
                4. 候选为空时 poi 填用户目的地关键词原样。
                严格返回 JSON：{"poi":"..."}。
            """.trimIndent(),
            user = { p ->
                "用户想去：${p["query"]}\n结果候选（| 分隔）：${p["candidates"].ifBlank { "（空）" }}"
            },
        )

        // 列表项若自带「路线」：点「路线」；否则点 LLM 从候选里选出的地点卡片
        // （「最近」由 AmapPoiResolver Web API / sortrule=distance 负责，不靠 UI 越靠上）
        find(ROUTE_BUTTON) {
            ok { click(ROUTE_BUTTON).wait() }
            miss { click(param("poi")).wait() }
        }

        // 详情底栏主按钮多为「导航」；路线规划页多为「开始导航」
        find(START_NAV) {
            ok { click(START_NAV).wait() }
            miss { click(NAV_BUTTON).wait() }
        }

        wait()

        find(START_NAV) {
            ok { click(START_NAV).wait() }
            miss { wait() }
        }

        finish { p -> "已为您规划导航前往：${p["query"]}" }
    }
}
