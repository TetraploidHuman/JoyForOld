package com.tetraploid.joyforold.agent.actionsets

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetParams
import com.tetraploid.joyforold.agent.actionsets.dsl.ParamSource
import com.tetraploid.joyforold.agent.actionsets.dsl.actionScript
import com.tetraploid.joyforold.agent.actionsets.dsl.param

/**
 * 微信发消息动作组（线性 actionScript，微信专用）。
 *
 * **设计目的**：固定「开微信 → 进会话 → 输入发送」本地执行，少把整棵微信 UI 树
 * 塞进主规划 LLM。会话/联系人列表会变化，故只采列表候选交给窄域 askLlm 选型。
 */
object ImSendMessageActionSet {
    const val ID = "wechat_send_im_message"
    const val WECHAT_APP = "微信"
    const val SEARCH_FIELD_HINT = "搜索本地或网络结果"
    const val SEARCH_ENTRY_LABEL = "搜索"

    val definition = actionScript(ID) {
        uiLabel { p ->
            val c = p["contact"]
            if (c.isNotBlank()) "动作组：微信给${c}发消息" else "动作组：微信发消息"
        }
        require("contact", from = ParamSource.INPUT_TEXT)
        require("message", from = ParamSource.MESSAGE)

        openApp(WECHAT_APP).wait()

        // 只采会话/联系人列表等会变片段 → 窄域选型（不传整棵 UI 树）
        captureTexts(into = "candidates")
        askLlm(
            writeTo = listOf("contact"),
            system = """
                你在帮老人从微信**会话/联系人列表**里选一个目标，供后续点击。
                输入只有：用户说的对象 + 当前页采到的列表候选（不是完整界面树）。
                任务：选出最符合用户意图的一条候选显示名。
                规则：
                1. contact 尽量完整拷贝候选原句，便于点击匹配。
                2. 优先选联系人/会话名，而不是底部 Tab、按钮杂项。
                3. 候选都不合适时 contact 填用户原话（后续会走搜索分支）。
                严格返回 JSON：{"contact":"..."}。
            """.trimIndent(),
            user = { p ->
                "用户想找的联系人：${p["contact"]}\n列表候选（| 分隔）：${p["candidates"].ifBlank { "（空）" }}"
            },
        )

        find(param("contact")) {
            ok { click(param("contact")).wait() }
            miss {
                click(SEARCH_ENTRY_LABEL)
                type(param("contact"), into = SEARCH_FIELD_HINT).wait()
                click(param("contact")).wait()
            }
        }

        label("chat")
        type(param("message"))
        send()
        finish { p -> "已尝试通过微信发送：${p["message"]}" }
    }

    /** 已在微信聊天页时：只注入输入 + 发送（供本地短指令）。 */
    fun stepsOnChatPage(message: String): List<AgentAction> =
        definition.resolveActions(
            "chat",
            ActionSetParams(mapOf("message" to message)),
        )
}
