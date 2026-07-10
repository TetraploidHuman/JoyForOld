package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.system.SystemIntentExecutor

object AgentToolRegistry {
    val toolNames: List<String> = listOf(
        "click", "type", "send", "scroll_down", "scroll_up", "back", "home", "wait",
        "find_on_page", "read_tree", "swipe_down", "list_apps", "open_app", "finish",
        "dial_contact", "send_sms", "set_alarm", "add_calendar_event",
        "open_camera", "open_gallery", "open_weather",
        "open_health_code", "open_payment_code", "open_font_settings",
        "navigate_home", "read_unread_messages", "ask_family_for_help", "emergency_help",
    )

    fun descriptionsForPrompt(): String = """
        可用工具（action 字段）：
        - click: 点击含 target_text 的可点击元素
        - type: 在输入框输入 input_text
        - send: 点击发送按钮
        - scroll_down / scroll_up: 在列表内滚动
        - swipe_down: 全屏下滑手势（列表滚不动时用）
        - list_apps: 读取本机已安装可打开应用；不确定应用名时先调用。target_text 可选，用于按关键词筛选
        - open_app: 打开应用，target_text 填应用中文名（须与 list_apps 返回的名称逐字一致）
        - dial_contact: 系统拨号，target_text 填联系人名或手机号
        - send_sms: 系统短信，target_text 填联系人，input_text 填短信内容
        - set_alarm: 系统闹钟，target_text 填时间（如 7:30），input_text 可填提醒标题
        - add_calendar_event: 新建日历事件，target_text 为标题，input_text 为备注
        - open_camera / open_gallery / open_weather: 打开相机/相册/天气
        - open_health_code / open_payment_code: 尝试打开健康码或付款码入口应用
        - open_font_settings: 打开字体显示设置
        - navigate_home: 用地图应用导航回家（从用户预设地址读取）
        - read_unread_messages: 读取未读消息（需通知监听）
        - ask_family_for_help / emergency_help: 家人协助能力
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
            "list_apps" -> service.listAppsResult(action.targetText)
            "swipe_down" -> {
                val msg = service.swipeDown()
                ActionExecutionResult(
                    success = !msg.contains("失败") && !msg.contains("取消"),
                    summary = msg,
                )
            }
            "dial_contact",
            "send_sms",
            "set_alarm",
            "add_calendar_event",
            "open_camera",
            "open_gallery",
            "open_weather",
            "open_health_code",
            "open_payment_code",
            "open_font_settings",
            "navigate_home",
            "read_unread_messages",
            "ask_family_for_help",
            "emergency_help",
            -> SystemIntentExecutor.execute(
                context = service,
                action = action.action,
                targetText = action.targetText,
                inputText = action.inputText,
            )
            else -> service.executeWithResult(action)
        }
    }
}
