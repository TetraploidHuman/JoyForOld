package com.tetraploid.joyforold.agent

/**
 * 将用户指令或预设动作链转为用户能看懂的任务阶段。
 */
object TaskPhasePlanner {
    private val openAndSend = Regex(
        """打开\s*(.+?)\s*给\s*(.+?)\s*发(?:消息|送)?(?:[:：\s]+|说)?(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val sendToPerson = Regex(
        """给\s*(.+?)\s*发(?:消息|送)?[:：\s]*(.+)""",
    )
    private val sendInAppContact = Regex(
        """(?:在)?(.+?)(?:里|中)(?:联系人)?(.+?)\s*发(?:消息|送)?[:：\s]*(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val dialPattern = Regex("""(?:给|向)?(.+?)(?:打电话|拨打|拨号)""")
    private val openWithAppName = Regex(
        """(?:请|帮我?)?(?:打开|启动|开启|运行|开一下)(?:应用)?\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val openSingleToken = Regex(
        """(?:请|帮我?)?(?:打开|启动|开启|运行|开一下)\s*([^\s，,。；;]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val clickPattern = Regex("""^(?:点击|点按|按(?:一下)?)\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val typePattern = Regex("""(?:输入|打字|写入)[:：]?\s*(.+)""", RegexOption.IGNORE_CASE)
    private val findPattern = Regex("""(?:搜索|查找|找)\s*(.+)""", RegexOption.IGNORE_CASE)
    private val scrollDownPattern = Regex("""(?:向下滚动|下滑|往下翻)""")
    private val scrollUpPattern = Regex("""(?:向上滚动|上滑|往上翻)""")

    fun planFromCommand(command: String): List<TaskPhaseItem> {
        val text = command.trim()
        if (text.isBlank()) return emptyList()

        planFromCategoryPrefix(text)?.let { return it }
        ElderTaskTemplateMatcher.match(text)?.let { return planFromActions(it) }
        parseSendFlow(text)?.let { return it }
        SystemIntentLocalParser.parse(text)?.let { return planFromActions(it) }
        LocalCommandParser.parse(text)?.let { return planFromActions(it) }

        dialPattern.find(text)?.let { match ->
            val who = cleanLabel(match.groupValues[1])
            if (who.isNotBlank()) {
                return phases("拨打${who}")
            }
        }

        openWithAppName.find(text)?.let { match ->
            val app = normalizeAppName(match.groupValues[1])
            if (app.isNotBlank()) {
                return phases("打开$app")
            }
        }

        openSingleToken.find(text)?.let { match ->
            val app = normalizeAppName(match.groupValues[1])
            if (app.isNotBlank() && app != "应用") {
                return phases("打开$app")
            }
        }

        if (text.contains("导航") || text.contains("回家")) {
            return phases("导航回家")
        }

        if (text.contains("读") && text.contains("消息")) {
            return phases("读取未读消息")
        }

        if (InfoQueryDetector.isTimeQuery(text)) {
            return phases("查看时间")
        }

        if (InfoQueryDetector.isWeatherQuery(text)) {
            return phases("查询天气")
        }

        clickPattern.find(text)?.let { match ->
            val target = cleanLabel(match.groupValues[1])
            if (target.isNotBlank()) return phases("点击「$target」")
        }

        typePattern.find(text)?.let { match ->
            val content = cleanLabel(match.groupValues[1])
            if (content.isNotBlank()) return phases("输入「$content」")
        }

        findPattern.find(text)?.let { match ->
            val keyword = cleanLabel(match.groupValues[1])
            if (keyword.isNotBlank()) return phases("搜索「$keyword」")
        }

        if (scrollDownPattern.containsMatchIn(text)) {
            return phases("向下滚动页面")
        }

        if (scrollUpPattern.containsMatchIn(text)) {
            return phases("向上滚动页面")
        }

        return phases(summarizeCommand(text))
    }

    fun planFromActions(actions: List<AgentAction>): List<TaskPhaseItem> {
        val labels = actions.mapNotNull { actionToPhaseLabel(it) }
            .distinct()
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) {
            return emptyList()
        }
        return phases(*labels.toTypedArray())
    }

    private fun planFromCategoryPrefix(text: String): List<TaskPhaseItem>? {
        return when {
            text.startsWith("打开应用 ") -> {
                val app = text.removePrefix("打开应用 ").trim()
                if (app.isBlank()) phases("查找并打开应用") else phases("打开${normalizeAppName(app)}")
            }
            text.startsWith("搜索文档 ") -> {
                val keyword = text.removePrefix("搜索文档 ").trim()
                if (keyword.isBlank()) null else phases("搜索文档「$keyword」")
            }
            text.startsWith("搜索网页 ") -> {
                val keyword = text.removePrefix("搜索网页 ").trim()
                if (keyword.isBlank()) null else phases("搜索网页「$keyword」")
            }
            else -> null
        }
    }

    private fun parseSendFlow(text: String): List<TaskPhaseItem>? {
        openAndSend.find(text)?.let { match ->
            val app = normalizeAppName(match.groupValues[1])
            val contact = cleanLabel(match.groupValues[2])
            val message = cleanLabel(match.groupValues[3])
            if (app.isNotBlank() && contact.isNotBlank() && message.isNotBlank()) {
                return phases("打开$app", "找到$contact", "发送「$message」")
            }
        }

        sendInAppContact.find(text)?.let { match ->
            val app = normalizeAppName(match.groupValues[1])
            val contact = cleanLabel(match.groupValues[2])
            val message = cleanLabel(match.groupValues[3])
            if (app.isNotBlank() && contact.isNotBlank() && message.isNotBlank()) {
                return phases("打开$app", "找到$contact", "发送「$message」")
            }
        }

        sendToPerson.find(text)?.let { match ->
            val contact = cleanLabel(match.groupValues[1])
            val message = cleanLabel(match.groupValues[2])
            if (contact.isNotBlank() && message.isNotBlank()) {
                val app = inferAppFromCommand(text)
                return phases("打开$app", "找到$contact", "发送「$message」")
            }
        }

        return null
    }

    private fun actionToPhaseLabel(action: AgentAction): String? {
        val name = action.action.lowercase()
        return when (name) {
            "open_app" -> "打开${action.targetText.orEmpty().ifBlank { "应用" }}"
            "open_camera" -> "打开相机"
            "open_gallery" -> "打开相册"
            "open_weather" -> "打开天气"
            "open_health_code" -> "打开健康码"
            "open_payment_code" -> "打开付款码"
            "open_font_settings", "open_display_settings" -> "打开显示设置"
            "open_settings" -> "打开系统设置"
            "open_wifi_settings" -> "打开无线网络"
            "open_bluetooth_settings" -> "打开蓝牙"
            "open_sound_settings" -> "打开声音设置"
            "open_mobile_data_settings" -> "打开移动数据"
            "open_location_settings" -> "打开定位设置"
            "navigate_home" -> "导航回家"
            "navigate_to" -> "导航前往${action.targetText.orEmpty()}"
            "dial_contact" -> "拨打${action.targetText.orEmpty()}"
            "send_sms" -> "给${action.targetText.orEmpty()}发短信"
            "read_unread_messages" -> "读取未读消息"
            "tell_time" -> "查看时间"
            "query_weather" -> "查询天气"
            "ask_family_for_help" -> "联系家人帮忙"
            "emergency_help" -> "紧急呼救"
            "list_apps" -> "查找应用"
            "find_on_page" -> "搜索「${action.targetText.orEmpty()}」"
            "click" -> when (action.targetText?.trim()) {
                "输入", "输入框" -> "打开输入框"
                null, "" -> null
                else -> {
                    val target = action.targetText.orEmpty()
                    if (target.length <= 8) "找到$target" else "点击「$target」"
                }
            }
            "type" -> "输入「${action.inputText.orEmpty()}」"
            "send" -> "发送消息"
            AgentActionSet.ACTION_RUN_ACTION_SET -> AgentActionSet.uiLabel(action)
            "scroll_down", "swipe_down" -> "向下滚动"
            "scroll_up" -> "向上滚动"
            "back" -> "返回上一页"
            "home" -> "回到桌面"
            "set_alarm" -> "设置闹钟 ${action.targetText.orEmpty()}"
            "wait", "read_tree", "finish" -> null
            else -> null
        }
    }

    private fun summarizeCommand(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= 20) compact else compact.take(20) + "…"
    }

    private fun phases(vararg labels: String): List<TaskPhaseItem> =
        fromLabels(labels.toList())

    /** 将阶段文案列表转为 UI 阶段（第 1 步 InProgress）。 */
    fun fromLabels(labels: List<String>): List<TaskPhaseItem> {
        val cleaned = sanitizePhaseLabels(labels)
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.mapIndexed { index, label ->
            TaskPhaseItem(
                index = index + 1,
                label = label.take(24),
                status = if (index == 0) TaskStepStatus.InProgress else TaskStepStatus.Pending,
            )
        }
    }

    /** 去掉「结束任务」等收尾措辞，避免排到第一步并误标为进行中。 */
    fun sanitizePhaseLabels(labels: List<String>): List<String> =
        labels.map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { isFinishLikePhase(it) }
            .distinct()

    fun isFinishLikePhase(label: String): Boolean {
        val t = label.trim()
        if (t.isEmpty()) return true
        val finishHints = listOf(
            "结束任务", "完成任务", "任务结束", "任务完成", "收尾",
            "播放后结束", "完成后结束", "结束流程",
        )
        if (finishHints.any { t.contains(it) }) return true
        if (t == "结束" || t == "完成" || t == "finish") return true
        return false
    }

    /**
     * 解析 LLM 返回的粗略阶段 JSON：
     * `{"phases":["打开微信","找到联系人","发送消息"]}`
     * 也兼容 `{"plan":[...]}` / 字符串数组顶层。
     */
    fun parseFromLlmJson(json: org.json.JSONObject): List<TaskPhaseItem> {
        val array = json.optJSONArray("phases")
            ?: json.optJSONArray("plan")
            ?: json.optJSONArray("stages")
        if (array == null || array.length() == 0) return emptyList()
        val labels = buildList {
            for (i in 0 until array.length()) {
                val asObj = array.optJSONObject(i)
                val label = when {
                    asObj != null -> asObj.optString("label").ifBlank { asObj.optString("title") }
                    else -> array.optString(i)
                }
                if (label.isNotBlank()) add(label)
            }
        }
        return fromLabels(labels.take(6))
    }

    private fun inferAppFromCommand(text: String): String {
        return when {
            text.contains("qq", ignoreCase = true) -> "QQ"
            text.contains("微信") -> "微信"
            text.contains("短信") -> "短信"
            else -> "微信"
        }
    }

    private fun normalizeAppName(raw: String): String {
        val cleaned = cleanLabel(raw)
        return when {
            cleaned.contains("qq", ignoreCase = true) -> "QQ"
            cleaned.contains("微信") -> "微信"
            cleaned.contains("支付宝") -> "支付宝"
            cleaned.contains("抖音") -> "抖音"
            cleaned.isBlank() -> ""
            else -> cleaned
        }
    }

    private fun cleanLabel(raw: String): String {
        return raw.trim()
            .removePrefix("给")
            .removePrefix("向")
            .removeSuffix("的")
            .trim()
    }
}

data class TaskPhaseItem(
    val index: Int,
    val label: String,
    val status: TaskStepStatus,
)

object TaskPhaseTracker {
    fun advanceFromAction(
        phases: List<TaskPhaseItem>,
        actionName: String?,
    ): List<TaskPhaseItem> {
        if (phases.isEmpty()) return phases
        val action = actionName?.lowercase().orEmpty()
        val keyword = when (action) {
            "open_app", "list_apps", "open_camera", "open_gallery", "open_weather",
            "open_health_code", "open_payment_code", "open_font_settings", "open_display_settings",
            "open_settings", "open_wifi_settings", "open_bluetooth_settings", "open_sound_settings",
            "open_mobile_data_settings", "open_location_settings",
            -> "打开"
            "find_on_page" -> "搜索"
            "click", "tap" -> phases.firstOrNull { it.label.startsWith("找到") }?.label?.take(2) ?: "找"
            "type" -> "输入"
            "send", "send_sms" -> "发送"
            AgentActionSet.ACTION_RUN_ACTION_SET -> "动作组"
            "dial_contact" -> "拨打"
            "navigate_home", "navigate_to" -> "导航"
            "read_unread_messages" -> "读取"
            "scroll_down", "swipe_down", "scroll_up" -> "滚动"
            "back" -> "返回"
            "home" -> "桌面"
            "ask_family_for_help" -> "家人"
            "emergency_help" -> "呼救"
            "set_alarm" -> "闹钟"
            "add_calendar_event" -> "日程"
            "finish" -> null
            else -> null
        }
        if (action == "finish") return markAllCompleted(phases)
        if (keyword != null) {
            val matched = completePhaseMatching(phases, keyword)
            if (matched !== phases) return matched
            // LLM 阶段文案可能不含关键字：推进当前进行中阶段
            return advanceCurrentPhase(phases)
        }
        // tap/type 等与阶段文案对不上时，仍推进进行中项，避免计划卡卡住
        return when (action) {
            "click", "tap", "type", "send", "wait" -> advanceCurrentPhase(phases)
            else -> phases
        }
    }

    fun markAllCompleted(phases: List<TaskPhaseItem>): List<TaskPhaseItem> {
        return phases.map { it.copy(status = TaskStepStatus.Completed) }
    }

    private fun advanceCurrentPhase(phases: List<TaskPhaseItem>): List<TaskPhaseItem> {
        val targetIdx = phases.indexOfFirst { it.status == TaskStepStatus.InProgress }
            .takeIf { it >= 0 } ?: phases.indexOfFirst { it.status == TaskStepStatus.Pending }
        if (targetIdx < 0) return phases
        return phases.mapIndexed { index, phase ->
            when {
                index == targetIdx -> phase.copy(status = TaskStepStatus.Completed)
                index == targetIdx + 1 && phase.status == TaskStepStatus.Pending ->
                    phase.copy(status = TaskStepStatus.InProgress)
                else -> phase
            }
        }
    }

    private fun completePhaseMatching(
        phases: List<TaskPhaseItem>,
        keyword: String,
    ): List<TaskPhaseItem> {
        val targetIdx = phases.indexOfFirst { phase ->
            phase.label.contains(keyword) && phase.status != TaskStepStatus.Completed
        }.takeIf { it >= 0 } ?: phases.indexOfFirst { it.status == TaskStepStatus.InProgress }
            .takeIf { it >= 0 } ?: phases.indexOfFirst { it.status == TaskStepStatus.Pending }

        if (targetIdx < 0) return phases

        return phases.mapIndexed { index, phase ->
            when {
                index < targetIdx && phase.status != TaskStepStatus.Completed ->
                    phase.copy(status = TaskStepStatus.Completed)
                index == targetIdx ->
                    phase.copy(status = TaskStepStatus.Completed)
                index == targetIdx + 1 && phase.status == TaskStepStatus.Pending ->
                    phase.copy(status = TaskStepStatus.InProgress)
                else -> phase
            }
        }
    }
}
