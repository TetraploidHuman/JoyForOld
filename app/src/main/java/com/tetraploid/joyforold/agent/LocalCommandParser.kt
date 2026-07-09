package com.tetraploid.joyforold.agent

object LocalCommandParser {
    private val clickPattern = Regex("""^(点击|点|按)\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val typeOnlyPattern = Regex("""^(输入|打字|写入)[:：]?\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val sendToPersonPattern = Regex("""^(给)?(.+?)(发消息|发送消息|发送|发)[:：\s]+(.+)$""")
    private val sendInChatPattern = Regex("""^(发送|发消息|发一条消息)[:：\s]+(.+)$""", RegexOption.IGNORE_CASE)
    private val confirmLastSendPattern =
        Regex("""^(发送上一条消息|发送刚才的消息|确认发送)$""", RegexOption.IGNORE_CASE)

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

        clickPattern.find(text)?.let { match ->
            val target = match.groupValues[2].trim()
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
            else -> null
        }
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
