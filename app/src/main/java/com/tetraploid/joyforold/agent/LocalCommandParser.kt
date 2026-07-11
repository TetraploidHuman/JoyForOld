package com.tetraploid.joyforold.agent

object LocalCommandParser {
    private val clickPattern = Regex("""^(?:点击|点按|按(?:一下)?)\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val typeOnlyPattern = Regex("""^(输入|打字|写入)[:：]?\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val sendToPersonPattern = Regex("""^(给)?(.+?)(发消息|发送消息|发送|发)[:：\s]+(.+)$""")
    private val sendInChatPattern = Regex("""^(发送|发消息|发一条消息)[:：\s]+(.+)$""", RegexOption.IGNORE_CASE)
    private val confirmLastSendPattern =
        Regex("""^(发送上一条消息|发送刚才的消息|确认发送)$""", RegexOption.IGNORE_CASE)
    private val alarmPattern = Regex("""^(设(个|置)?闹钟|提醒我)\s*(\d{1,2}:\d{2})\s*(.*)$""")
    private val smsPattern = Regex("""^(给)?(.+?)(发短信|短信)[:：\s]+(.+)$""")
    private val weatherQueryPattern = Regex("""^(查看|查|今天|现在)?天气(怎么样|如何)?$""")
    private val weatherCityPattern = Regex("""^(.+?)的天气(怎么样|如何)?$""")

    fun isSendToSpecificPerson(command: String): Boolean {
        return sendToPersonPattern.matches(command.trim())
    }

    fun parse(command: String): List<AgentAction>? {
        val text = command.trim()
        if (text.isEmpty()) return null

        if (confirmLastSendPattern.matches(text)) {
            return listOf(
                AgentAction(action = "send"),
                AgentAction(action = "finish", message = "已尝试发送上一条消息", finished = true),
            )
        }

        sendToPersonPattern.find(text)?.let { match ->
            val target = match.groupValues[2].trim()
            val message = match.groupValues[4].trim()
            if (target.isNotBlank() && message.isNotBlank()) {
                return sendMessageSteps(message, contact = target)
            }
        }

        sendInChatPattern.find(text)?.let { match ->
            val message = match.groupValues[2].trim()
            if (message.isNotBlank()) {
                return sendMessageSteps(message)
            }
        }

        smsPattern.find(text)?.let { match ->
            val target = match.groupValues[2].trim()
            val body = match.groupValues[4].trim()
            if (target.isNotBlank() && body.isNotBlank()) {
                return listOf(
                    AgentAction(action = "send_sms", targetText = target, inputText = body),
                    AgentAction(action = "finish", message = "已为 $target 准备短信发送页面", finished = true),
                )
            }
        }

        alarmPattern.find(text)?.let { match ->
            val time = match.groupValues[3].trim()
            val label = match.groupValues[4].trim()
            if (time.isNotBlank()) {
                return listOf(
                    AgentAction(action = "set_alarm", targetText = time, inputText = label),
                    AgentAction(action = "finish", message = "已打开闹钟设置：$time", finished = true),
                )
            }
        }

        weatherCityPattern.find(text)?.let { match ->
            val city = match.groupValues[1].trim()
            if (city.isNotBlank()) {
                return infoQuerySteps("query_weather", city)
            }
        }

        if (weatherQueryPattern.matches(text)) {
            return infoQuerySteps("query_weather")
        }

        if (InfoQueryDetector.isTimeQuery(text)) {
            return infoQuerySteps("tell_time")
        }

        clickPattern.find(text)?.let { match ->
            val target = match.groupValues[1].trim()
            if (target.isNotBlank()) {
                return listOf(
                    AgentAction(action = "click", targetText = target),
                    AgentAction(action = "finish", message = "已点击：$target", finished = true),
                )
            }
        }

        typeOnlyPattern.find(text)?.let { match ->
            val content = match.groupValues[2].trim()
            if (content.isNotBlank()) {
                return if (SendIntentDetector.isSendCommand(text)) {
                    sendMessageSteps(content)
                } else {
                    listOf(
                        AgentAction(action = "type", inputText = content),
                        AgentAction(action = "finish", message = "已输入", finished = true),
                    )
                }
            }
        }

        return when (text.lowercase()) {
            "返回", "后退", "back" -> listOf(
                AgentAction(action = "back"),
                AgentAction(action = "finish", message = "已返回", finished = true),
            )
            "首页", "桌面", "home" -> listOf(
                AgentAction(action = "home"),
                AgentAction(action = "finish", message = "已回桌面", finished = true),
            )
            "向下滚动", "下滑", "scroll down" -> listOf(
                AgentAction(action = "scroll_down"),
                AgentAction(action = "finish", message = "已向下滚动", finished = true),
            )
            "向上滚动", "上滑", "scroll up" -> listOf(
                AgentAction(action = "scroll_up"),
                AgentAction(action = "finish", message = "已向上滚动", finished = true),
            )
            "打开相机", "拍照" -> listOf(
                AgentAction(action = "open_camera"),
                AgentAction(action = "finish", message = "已打开相机", finished = true),
            )
            "打开相册" -> listOf(
                AgentAction(action = "open_gallery"),
                AgentAction(action = "finish", message = "已打开相册", finished = true),
            )
            "看天气", "打开天气" -> listOf(
                AgentAction(action = "open_weather"),
                AgentAction(action = "finish", message = "已打开天气", finished = true),
            )
            "查看时间", "几点了", "现在几点", "现在几点钟了", "几点钟了", "报时", "什么时间" -> infoQuerySteps("tell_time")
            "查看天气", "查天气", "今天天气", "今天天气怎么样", "帮我查一下天气" -> infoQuerySteps("query_weather")
            "紧急呼救", "sos" -> listOf(
                AgentAction(action = "emergency_help"),
                AgentAction(action = "finish", message = "已执行紧急呼救流程", finished = true),
            )
            else -> null
        }
    }

    private fun infoQuerySteps(action: String, targetText: String? = null): List<AgentAction> {
        return listOf(
            AgentAction(action = action, targetText = targetText),
            AgentAction(action = "finish", message = "正在查询", finished = true),
        )
    }

    private fun sendMessageSteps(message: String, contact: String? = null): List<AgentAction> {
        return buildList {
            if (!contact.isNullOrBlank()) {
                add(AgentAction(action = "click", targetText = contact))
                add(AgentAction(action = "click", targetText = "输入"))
                add(AgentAction(action = "type", inputText = message))
                add(
                    AgentAction(
                        action = "finish",
                        message = "已为 $contact 准备好消息：$message，请确认无误后再说「发送上一条消息」。",
                        finished = true,
                        waitingForUser = true,
                    ),
                )
                return@buildList
            }

            add(AgentAction(action = "click", targetText = "输入"))
            add(AgentAction(action = "type", inputText = message))
            add(AgentAction(action = "send"))
            add(AgentAction(action = "finish", message = "已尝试发送：$message", finished = true))
        }
    }
}
