package com.tetraploid.joyforold.agent

/**
 * 意图/动作能力表：路由分层 + 上下文按需传递 + 离线 NLU 门控。
 *
 * 产品原则（对标 Cortana 巅峰期）：
 * - 本地只管少数、确定、可逆的系统动作
 * - 复杂/问答走 DeepSeek
 * - 宁可 NLU 失败，不接受错误匹配
 */
object IntentCapabilityMatrix {

    enum class RouteTier {
        /** 模板 / 正则 / 高门槛离线 NLU */
        LOCAL_FAST,
        /** DeepSeek 系统意图分类（闹钟/日程等） */
        CLOUD_SYSTEM,
        /** 完整 Agent 环（UI 自动化 + 开放问答） */
        CLOUD_AGENT,
    }

    enum class PageContextNeed {
        /** 不传页面上下文（纯系统动作、开放问答） */
        NONE,
        /** 仅一行摘要（时间/天气等） */
        MINIMAL,
        /** 需要无障碍页面结构（微信发消息、点按钮等） */
        UI_FULL,
    }

    enum class ConfirmPolicy {
        NONE,
        VOICE_BINARY,
    }

    enum class RiskTier {
        LOW,
        MEDIUM,
        HIGH,
    }

    data class Capability(
        val id: String,
        val routeTier: RouteTier,
        val pageContextNeed: PageContextNeed,
        val requiresNetwork: Boolean,
        val requiresAccessibility: Boolean,
        val allowedOfflineNlu: Boolean,
        val offlineNluMinConfidence: Float = 0.88f,
        val offlineNluMinMargin: Float = 0.28f,
        val confirmPolicy: ConfirmPolicy = ConfirmPolicy.NONE,
        val riskTier: RiskTier = RiskTier.LOW,
    )

    data class RouteEnvironment(
        val hasNetwork: Boolean,
        val hasAccessibility: Boolean,
        val online: Boolean = hasNetwork,
    )

    private val byId: Map<String, Capability> = buildMap {
        fun putCap(cap: Capability) {
            put(cap.id, cap)
        }

        // --- 系统设置（低风险、本地快路径）---
        listOf(
            "open_wifi_settings",
            "open_bluetooth_settings",
            "open_sound_settings",
            "open_location_settings",
            "open_display_settings",
            "open_settings",
            "open_font_settings",
        ).forEach { id ->
            putCap(
                Capability(
                    id = id,
                    routeTier = RouteTier.LOCAL_FAST,
                    pageContextNeed = PageContextNeed.NONE,
                    requiresNetwork = false,
                    requiresAccessibility = false,
                    allowedOfflineNlu = true,
                    offlineNluMinConfidence = 0.88f,
                    offlineNluMinMargin = 0.28f,
                ),
            )
        }

        putCap(
            Capability(
                id = "open_mobile_data_settings",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                riskTier = RiskTier.MEDIUM,
            ),
        )

        // --- 应用 / 媒体 ---
        putCap(
            Capability(
                id = "open_app",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                riskTier = RiskTier.MEDIUM,
            ),
        )
        putCap(
            Capability(
                id = "open_camera",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.90f,
                offlineNluMinMargin = 0.30f,
                riskTier = RiskTier.MEDIUM,
            ),
        )
        putCap(
            Capability(
                id = "open_gallery",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.90f,
                offlineNluMinMargin = 0.30f,
                riskTier = RiskTier.MEDIUM,
            ),
        )
        putCap(
            Capability(
                id = "open_weather",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
            ),
        )

        // --- 信息查询 ---
        putCap(
            Capability(
                id = "tell_time",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
            ),
        )
        putCap(
            Capability(
                id = "query_weather",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.MINIMAL,
                requiresNetwork = true,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.90f,
                offlineNluMinMargin = 0.30f,
                riskTier = RiskTier.MEDIUM,
            ),
        )

        // --- 日程 / 闹钟（可本地可云分类）---
        putCap(
            Capability(
                id = "set_alarm",
                routeTier = RouteTier.CLOUD_SYSTEM,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                riskTier = RiskTier.MEDIUM,
            ),
        )
        putCap(
            Capability(
                id = "add_calendar_event",
                routeTier = RouteTier.CLOUD_SYSTEM,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                riskTier = RiskTier.MEDIUM,
            ),
        )

        // --- 通讯 / 紧急（高风险，须确认）---
        putCap(
            Capability(
                id = "dial_contact",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                confirmPolicy = ConfirmPolicy.VOICE_BINARY,
                riskTier = RiskTier.HIGH,
            ),
        )
        putCap(
            Capability(
                id = "send_sms",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = false,
                confirmPolicy = ConfirmPolicy.VOICE_BINARY,
                riskTier = RiskTier.HIGH,
            ),
        )
        putCap(
            Capability(
                id = "emergency_help",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.94f,
                offlineNluMinMargin = 0.40f,
                confirmPolicy = ConfirmPolicy.VOICE_BINARY,
                riskTier = RiskTier.HIGH,
            ),
        )
        putCap(
            Capability(
                id = "ask_family_for_help",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                confirmPolicy = ConfirmPolicy.VOICE_BINARY,
                riskTier = RiskTier.HIGH,
            ),
        )

        // --- 支付 / 健康码 ---
        putCap(
            Capability(
                id = "open_health_code",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                riskTier = RiskTier.MEDIUM,
            ),
        )
        putCap(
            Capability(
                id = "open_payment_code",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
                offlineNluMinConfidence = 0.92f,
                offlineNluMinMargin = 0.35f,
                confirmPolicy = ConfirmPolicy.VOICE_BINARY,
                riskTier = RiskTier.HIGH,
            ),
        )

        putCap(
            Capability(
                id = "navigate_home",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.MINIMAL,
                requiresNetwork = true,
                requiresAccessibility = false,
                allowedOfflineNlu = true,
            ),
        )

        putCap(
            Capability(
                id = "read_unread_messages",
                routeTier = RouteTier.LOCAL_FAST,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = false,
                requiresAccessibility = false,
                allowedOfflineNlu = false,
            ),
        )

        // --- UI Agent 工具 ---
        listOf("click", "type", "send", "scroll_down", "scroll_up", "back", "home", "wait",
            "find_on_page", "read_tree", "swipe_down", "list_apps",
        ).forEach { id ->
            putCap(
                Capability(
                    id = id,
                    routeTier = RouteTier.CLOUD_AGENT,
                    pageContextNeed = PageContextNeed.UI_FULL,
                    requiresNetwork = true,
                    requiresAccessibility = true,
                    allowedOfflineNlu = false,
                ),
            )
        }

        putCap(
            Capability(
                id = "none",
                routeTier = RouteTier.CLOUD_AGENT,
                pageContextNeed = PageContextNeed.NONE,
                requiresNetwork = true,
                requiresAccessibility = false,
                allowedOfflineNlu = false,
            ),
        )
    }

    private val complexQuery = Regex(
        """(怎么办|怎么用|如何使用|为什么|啥意思|什么意思|能不能|是不是|该不该|会不会|要不要|可不可以|咋样|怎么样|如何|[吗呢么][？?]?|[？?]$)""",
    )

    private val uiAutomationHints = Regex(
        """(点击|输入|发送|滑动|搜索|查找|读消息|念|短信|聊天|微信|支付宝|视频通话|发消息)""",
    )

    fun forIntent(intentId: String): Capability? = byId[intentId.lowercase()]

    fun forAction(action: String): Capability? = byId[action.lowercase()]

    fun primaryActionOf(steps: List<AgentAction>): String? =
        steps.firstOrNull { !it.action.equals("finish", ignoreCase = true) }?.action?.lowercase()

    fun capabilityForSteps(steps: List<AgentAction>): Capability? {
        val action = primaryActionOf(steps) ?: return null
        return forAction(action)
    }

    fun inferPageContextNeed(command: String): PageContextNeed {
        val text = command.trim()
        if (text.isBlank()) return PageContextNeed.NONE
        if (complexQuery.containsMatchIn(text) && !uiAutomationHints.containsMatchIn(text)) {
            return PageContextNeed.NONE
        }
        if (uiAutomationHints.containsMatchIn(text)) return PageContextNeed.UI_FULL
        return PageContextNeed.NONE
    }

    fun pageContextModeForNeed(need: PageContextNeed, dynamicMode: PageContextMode): PageContextMode =
        when (need) {
            PageContextNeed.NONE -> PageContextMode.NONE
            PageContextNeed.MINIMAL -> PageContextMode.DIFF_ONLY
            PageContextNeed.UI_FULL -> dynamicMode
        }

    fun passesOfflineNluGate(
        intentId: String,
        modelConfidence: Float,
        modelMargin: Float,
    ): Boolean {
        val cap = forIntent(intentId) ?: return false
        if (!cap.allowedOfflineNlu) return false
        return modelConfidence >= cap.offlineNluMinConfidence &&
            modelMargin >= cap.offlineNluMinMargin
    }

    fun isComplexQuery(command: String): Boolean {
        val text = command.trim()
        if (text.isBlank()) return false
        if (complexQuery.containsMatchIn(text)) return true
        return text.length > 24 && text.contains("怎么")
    }

    fun isRouteAllowed(route: CommandRouteResolver.Route, env: RouteEnvironment): Boolean {
        if (route.source == "offline_nlu" || route.source == "local_system" || route.source == "template") {
            // Handled by dedicated gates; viability checked via step capabilities.
        }
        if ((route.source == "system_ai" || route.source == "preset_ai") && !env.hasNetwork) {
            return false
        }
        val cap = capabilityForSteps(route.steps) ?: return true
        if (cap.requiresNetwork && !env.hasNetwork) return false
        if (cap.requiresAccessibility && !env.hasAccessibility) {
            return AgentToolRegistry.isSystemIntentOnly(route.steps)
        }
        return true
    }

    fun shouldUseOfflineNlu(
        command: String,
        intentId: String?,
        routeConfidence: Double,
        env: RouteEnvironment,
    ): Boolean {
        if (intentId.isNullOrBlank()) return false
        val cap = forIntent(intentId) ?: return false
        if (!cap.allowedOfflineNlu) return false
        if (isComplexQuery(command)) return false
        if (env.online && routeConfidence < 0.94) return false
        return true
    }

    fun toolsPromptForContext(need: PageContextNeed): String = when (need) {
        PageContextNeed.UI_FULL -> AgentToolRegistry.descriptionsForPrompt()
        PageContextNeed.MINIMAL -> systemToolsOnlyPrompt()
        PageContextNeed.NONE -> systemToolsOnlyPrompt()
    }

    private fun systemToolsOnlyPrompt(): String = """
        可用工具（action 字段）：
        - open_settings / open_wifi_settings / open_bluetooth_settings / open_sound_settings / open_mobile_data_settings / open_location_settings / open_display_settings: 打开对应系统设置页
        - dial_contact: 系统拨号，target_text 填联系人名或手机号
        - send_sms: 系统短信，target_text 填联系人，input_text 填短信内容
        - set_alarm: 系统闹钟，target_text 填时间（如 7:30），input_text 可填提醒标题
        - add_calendar_event: 新建日历事件，target_text 为标题，input_text 为备注
        - open_camera / open_gallery / open_weather / open_app: 打开相机/相册/天气/应用
        - tell_time / query_weather: 查时间/天气
        - open_health_code / open_payment_code: 健康码/付款码
        - navigate_home / ask_family_for_help / emergency_help: 导航回家/家人协助/紧急呼救
        - finish: 结束；waiting_for_user:true 时等待用户回复
        返回 JSON：{"action":"...","target_text":"","input_text":"","message":"","finished":false,"waiting_for_user":false,"needs_binary_confirm":false}
    """.trimIndent()
}
