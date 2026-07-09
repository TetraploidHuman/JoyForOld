package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService

object AgentToolRegistry {
    val toolNames: List<String> = listOf(
        "click", "type", "send", "scroll_down", "scroll_up", "back", "home", "wait",
        "find_on_page", "read_tree", "swipe_down", "open_app", "finish",
    )

    fun descriptionsForPrompt(): String = """
        可用工具（action 字段）：
        - click: 点击含 target_text 的可点击元素
        - type: 在输入框输入 input_text
        - send: 点击发送按钮
        - scroll_down / scroll_up: 在列表内滚动
        - swipe_down: 全屏下滑手势（列表滚不动时用）
        - open_app: 打开应用，target_text 填 QQ、微信、电话 等
        - back / home: 系统返回/桌面
        - wait: 等待界面刷新
        - find_on_page: 仅搜索不点击，target_text 为关键词，结果在下一步反馈里
        - read_tree: 读取当前页结构树片段（元素找不到时用）
        - finish: 结束；waiting_for_user:true 时向用户提问

        返回 JSON：{"action":"...","target_text":"","input_text":"","message":"","finished":false,"waiting_for_user":false}
    """.trimIndent()

    suspend fun execute(
        service: JoyAccessibilityService,
        action: AgentAction,
    ): ActionExecutionResult {
        return when (action.action.lowercase()) {
            "find_on_page" -> service.findOnPage(action.targetText)
            "read_tree" -> service.readTreeSnippet()
            "swipe_down" -> {
                val msg = service.swipeDown()
                ActionExecutionResult(
                    success = !msg.contains("失败") && !msg.contains("取消"),
                    summary = msg,
                )
            }
            else -> service.executeWithResult(action)
        }
    }
}
