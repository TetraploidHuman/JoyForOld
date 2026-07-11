package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.system.SystemIntentExecutor

object AgentToolRegistry {
    private val systemIntentActions = setOf(
        "dial_contact", "send_sms", "set_alarm", "add_calendar_event",
        "open_camera", "open_gallery", "open_weather", "open_app",
        "open_health_code", "open_payment_code", "open_font_settings", "open_display_settings",
        "open_settings", "open_wifi_settings", "open_bluetooth_settings", "open_sound_settings",
        "open_mobile_data_settings", "open_location_settings",
        "navigate_home", "read_unread_messages", "tell_time", "query_weather",
        "ask_family_for_help", "emergency_help",
    )

    fun isSystemIntentAction(action: String): Boolean =
        action.lowercase() in systemIntentActions

    fun isSystemIntentOnly(steps: List<AgentAction>): Boolean =
        steps.filterNot { it.action.equals("finish", ignoreCase = true) }
            .all { isSystemIntentAction(it.action) }

    val toolNames: List<String> = listOf(
        "click", "type", "send", "scroll_down", "scroll_up", "back", "home", "wait",
        "find_on_page", "read_tree", "swipe_down", "list_apps", "open_app", "finish",
        "dial_contact", "send_sms", "set_alarm", "add_calendar_event",
        "open_camera", "open_gallery", "open_weather", "open_app",
        "open_health_code", "open_payment_code", "open_font_settings", "open_display_settings",
        "open_settings", "open_wifi_settings", "open_bluetooth_settings", "open_sound_settings",
        "open_mobile_data_settings", "open_location_settings",
        "navigate_home", "read_unread_messages", "tell_time", "query_weather",
        "ask_family_for_help", "emergency_help",
    )

    fun descriptionsForPrompt(): String = """
        可用工具（action 字段）：
        - click: 点击含 target_text 的可点击元素
        - type: 在输入框输入 input_text
        - send: 点击发送按钮
        - scroll_down / scroll_up: 在列表内滚动
        - swipe_down: 全屏下滑手势（列表滚不动时用）
        - list_apps: 读取本机已安装可打开应用；不确定应用名时先调用。target_text 可选，用于按关键词筛选
        - open_app: 用系统启动器打开应用，target_text 填应用中文名
        - open_settings / open_wifi_settings / open_bluetooth_settings / open_sound_settings / open_mobile_data_settings / open_location_settings / open_display_settings: 打开对应系统设置页
        - dial_contact: 系统拨号，target_text 填联系人名或手机号
        - send_sms: 系统短信，target_text 填联系人，input_text 填短信内容
        - set_alarm: 系统闹钟，target_text 填时间（如 7:30），input_text 可填提醒标题
        - add_calendar_event: 新建日历事件，target_text 为标题，input_text 为备注
        - open_camera / open_gallery / open_weather: 打开相机/相册/天气
        - tell_time: 查看当前时间并朗读
        - query_weather: 查询天气并朗读；target_text 可选填城市
        - open_health_code / open_payment_code: 尝试打开健康码或付款码入口应用
        - open_font_settings: 打开字体显示设置
        - navigate_home: 用地图应用导航回家（从用户预设地址读取）
        - read_unread_messages: 读取未读消息（需通知监听）
        - ask_family_for_help / emergency_help: 家人协助能力
        - back / home: 系统返回/桌面
        - wait: 等待界面刷新
        - find_on_page: 仅搜索不点击，target_text 为关键词，结果在下一步反馈里
        - read_tree: 读取当前页结构树片段（元素找不到时用）
        - finish: 结束；waiting_for_user:true 时等待用户回复；needs_binary_confirm:true 时须用户说发送/取消
        - **finish 与等待用户（由你显式标记，禁止靠标点猜测）**：
          · 闲聊/问候/任务已完成：finished:true, waiting_for_user:false, needs_binary_confirm:false
          · 需用户补充信息（开放问答）：waiting_for_user:true, needs_binary_confirm:false
          · 敏感操作二选一（发送/取消、拨号确认）：waiting_for_user:true, needs_binary_confirm:true

        返回 JSON：{"action":"...","target_text":"","input_text":"","message":"","finished":false,"waiting_for_user":false,"needs_binary_confirm":false}
    """.trimIndent()

    suspend fun execute(
        service: JoyAccessibilityService,
        action: AgentAction,
    ): ActionExecutionResult = executeSystemIntent(service, action) ?: service.executeWithResult(action)

    suspend fun executeSystemIntent(
        context: android.content.Context,
        action: AgentAction,
    ): ActionExecutionResult? {
        if (!isSystemIntentAction(action.action)) return null
        return SystemIntentExecutor.execute(
            context = context,
            action = action.action,
            targetText = action.targetText,
            inputText = action.inputText,
        )
    }
}
