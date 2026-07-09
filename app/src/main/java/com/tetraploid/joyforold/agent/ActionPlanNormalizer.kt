package com.tetraploid.joyforold.agent

object SendIntentDetector {
    private val sendKeywords = Regex("""(发消息|发送消息|发送|发给|发一条|留言|说出|回复)""", RegexOption.IGNORE_CASE)

    fun isSendCommand(command: String): Boolean = sendKeywords.containsMatchIn(command.trim())

    fun extractMessageContent(command: String): String? {
        val text = command.trim()
        val patterns = listOf(
            Regex("""^(给)?(.+?)(发消息|发送消息|发送|发)[:：\s]+(.+)$"""),
            Regex("""^(发送|发消息|发一条消息)[:：\s]+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^发送(.+)$"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val content = when (match.groupValues.size) {
                5 -> match.groupValues[4]
                3 -> match.groupValues[2]
                2 -> match.groupValues[1]
                else -> ""
            }.trim()
            if (content.isNotBlank() && content.length <= 500) {
                return content
            }
        }
        return null
    }
}

object ActionPlanNormalizer {
    private val sendClickTargets = listOf("发送", "send", "发表", "送出", "发送(按钮)")

    fun normalize(userCommand: String, steps: List<AgentAction>): List<AgentAction> {
        if (!SendIntentDetector.isSendCommand(userCommand)) {
            return steps
        }

        val hasSendClick = steps.any { step ->
            step.action.equals("send", ignoreCase = true) ||
                (step.action.equals("click", ignoreCase = true) &&
                    sendClickTargets.any { target ->
                        step.targetText?.contains(target, ignoreCase = true) == true
                    })
        }
        val hasType = steps.any { it.action.equals("type", ignoreCase = true) }

        if (hasSendClick || !hasType) {
            return steps
        }

        // 关键：当是“给某人发消息”这种场景时，不允许自动补发。
        // 需要通过交互确认（本地/AI 已准备好消息后，等用户说“发送上一条消息”再 send）。
        if (LocalCommandParser.isSendToSpecificPerson(userCommand)) {
            val normalized = steps
                .filterNot { it.action.equals("finish", ignoreCase = true) || it.finished }
                .toMutableList()

            val messageText = steps.firstOrNull { it.action.equals("type", ignoreCase = true) }?.inputText
            val contactText = steps.firstOrNull { step ->
                step.action.equals("click", ignoreCase = true) &&
                    !step.targetText.isNullOrBlank() &&
                    step.targetText.equals("输入", ignoreCase = true) == false
            }?.targetText

            val contactPart = contactText?.let { "给 $it" } ?: "给指定联系人"
            val msgPart = messageText?.let { "：$it" } ?: ""

            normalized += AgentAction(
                action = "finish",
                message = "${contactPart}已准备好消息$msgPart。请确认后说「发送上一条消息」。",
                finished = true,
                waitingForUser = true,
            )
            return normalized
        }

        val normalized = steps
            .filterNot { it.action.equals("finish", ignoreCase = true) || it.finished }
            .toMutableList()

        normalized += AgentAction(action = "send")
        normalized += AgentAction(
            action = "finish",
            message = "已输入并点击发送",
            finished = true,
        )
        return normalized
    }
}
