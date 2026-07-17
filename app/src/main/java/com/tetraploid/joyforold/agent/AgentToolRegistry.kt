package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.AccessibilityActionDispatcher
import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.system.SystemIntentExecutor

object AgentToolRegistry {
    private val systemIntentActions = setOf(
        "dial_contact", "send_sms", "set_alarm", "add_calendar_event",
        "open_camera", "open_gallery", "open_weather", "open_app",
        "open_health_code", "open_payment_code", "open_font_settings", "open_display_settings",
        "open_settings", "open_wifi_settings", "open_bluetooth_settings", "open_sound_settings",
        "open_mobile_data_settings", "open_location_settings",
        "navigate_home", "navigate_to", "navigate_pick", "read_unread_messages", "tell_time", "query_weather",
        "ask_family_for_help", "emergency_help",
    )

    fun isSystemIntentAction(action: String): Boolean =
        action.lowercase() in systemIntentActions

    fun isSystemIntentOnly(steps: List<AgentAction>): Boolean =
        steps.filterNot { it.action.equals("finish", ignoreCase = true) }
            .all { isSystemIntentAction(it.action) }

    val toolNames: List<String> = listOf(
        "click", "tap", "type", "send", "scroll_down", "scroll_up", "back", "home", "wait",
        "find_on_page", "read_tree",
        AgentObservationQueries.ACTION_QUERY_PAGE,
        AgentObservationQueries.ACTION_QUERY_DIFF,
        AgentObservationQueries.ACTION_QUERY_TREE,
        "swipe_down", "list_apps", "open_app", "finish",
        "dial_contact", "send_sms", "set_alarm", "add_calendar_event",
        "open_camera", "open_gallery", "open_weather", "open_app",
        "open_health_code", "open_payment_code", "open_font_settings", "open_display_settings",
        "open_settings", "open_wifi_settings", "open_bluetooth_settings", "open_sound_settings",
        "open_mobile_data_settings", "open_location_settings",
        "navigate_home", "navigate_to", "navigate_pick", "read_unread_messages", "tell_time", "query_weather",
        "ask_family_for_help", "emergency_help",
        AgentActionSet.ACTION_RUN_ACTION_SET,
    )

    fun descriptionsForPrompt(visionMode: Boolean = false): String = """
        可用工具（action 字段）：
        - click: 点击含 target_text 的可点击元素${if (visionMode) "（当前不可用，请用 tap）" else "（无障碍树可用时优先）"}
        - tap: 按屏幕坐标点击；target_text 填归一化坐标 "x,y"（0~1000，左上为原点）${if (visionMode) "【当前须用 tap】" else "（无障碍树为空、消息带截图时用）"}
        - type: 在输入框输入 input_text；视觉模式下 target_text 可填输入框坐标 "x,y"（将原子点击并注入，优先 Joy IME）
        - send: 点击发送按钮；视觉模式下 target_text 可填发送按钮坐标 "x,y"
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
        - navigate_to: 直达导航（target_text=目的地；相对某地标时 input_text=地标，如 target=肯德基 input=桂阳一中）
        - navigate_pick: 先列候选再导航（同上可用 input_text 表示地标）
        - read_unread_messages: 读取未读消息（需通知监听）
        - ask_family_for_help / emergency_help: 家人协助能力
        - back / home: 系统返回/桌面
        - wait: 等待界面刷新
        - find_on_page: ${if (visionMode) "当前不可用（无障碍树为空）" else "仅搜索不点击，target_text 为关键词，结果在下一步反馈里"}
        - read_tree: ${if (visionMode) "当前不可用（无障碍树为空）" else "读取当前页结构树片段（元素找不到时用；结果会写入本地观察仓）"}
        ${AgentObservationQueries.descriptionsForPrompt(visionMode)}
        - finish: 结束；waiting_for_user:true 时等待用户回复；needs_binary_confirm:true 时须用户说发送/取消
        ${AgentActionSet.descriptionsForPrompt()}
        - **finish 与等待用户（由你显式标记，禁止靠标点猜测）**：
          · 闲聊/问候/任务已完成：finished:true, waiting_for_user:false, needs_binary_confirm:false
          · 需用户补充信息（开放问答）：waiting_for_user:true, needs_binary_confirm:false
          · 敏感操作二选一（发送/取消、拨号确认）：waiting_for_user:true, needs_binary_confirm:true

        返回 JSON：{"action":"...","target_text":"","input_text":"","message":"","finished":false,"waiting_for_user":false,"needs_binary_confirm":false}
    """.trimIndent()

    suspend fun execute(
        context: android.content.Context,
        service: AccessibilityGateway,
        action: AgentAction,
        observationStore: AgentObservationStore? = null,
    ): ActionExecutionResult {
        executeSystemIntent(context, action)?.let { return it }

        if (AgentObservationQueries.isObservationQuery(action.action)) {
            return AgentObservationQueries.execute(
                action = action,
                store = observationStore,
                liveTreeFetcher = {
                    AccessibilityActionDispatcher.runAction {
                        service.executeWithResult(AgentAction(action = "read_tree"))
                    }
                },
            )
        }

        if (action.action.equals("swipe_down", ignoreCase = true)) {
            val summary = service.swipeDown()
            return ActionExecutionResult(
                success = !summary.contains("失败"),
                summary = summary,
                suggestions = if (summary.contains("失败")) {
                    listOf("尝试 scroll_down 在列表内滚动")
                } else {
                    emptyList()
                },
            )
        }
        if (action.action.equals("swipe_up", ignoreCase = true)) {
            val summary = service.swipeUp()
            return ActionExecutionResult(
                success = !summary.contains("失败"),
                summary = summary,
                suggestions = if (summary.contains("失败")) {
                    listOf("尝试 scroll_up 在列表内滚动")
                } else {
                    emptyList()
                },
            )
        }
        val result = AccessibilityActionDispatcher.runAction {
            service.executeWithResult(action)
        }
        if (action.action.equals("read_tree", ignoreCase = true)) {
            AgentObservationQueries.rememberReadTree(observationStore, result)
        }
        return result
    }

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
