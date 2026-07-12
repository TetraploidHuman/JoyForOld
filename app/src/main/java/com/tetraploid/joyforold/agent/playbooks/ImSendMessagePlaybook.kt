package com.tetraploid.joyforold.agent.playbooks

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentStepRecord

/**
 * 固定剧本：在 IM 应用（默认微信）里搜索联系人并发送消息（视觉 tap 坐标）。
 */
object ImSendMessagePlaybook {
    const val DEFAULT_IM_APP = "微信"

    data class Intent(
        val contact: String,
        val message: String,
        val appName: String = DEFAULT_IM_APP,
    )

    private val openAppAndSend = Regex(
        """打开\s*(.+?)\s*给\s*(.+?)\s*发(?:消息|送)?(?:[:：\s]+|说)?(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val sendToPerson = Regex("""^(给)?(.+?)(发消息|发送消息|发送|发)[:：\s]+(.+)$""")
    private val sendViaNamedApp = Regex(
        """给\s*(.+?)\s*发\s*(?:微信|qq)(?:消息)?[:：\s]*(.+)""",
        RegexOption.IGNORE_CASE,
    )

    const val SEARCH_X = 500
    const val SEARCH_Y = 85
    const val FIRST_RESULT_X = 500
    const val FIRST_RESULT_Y = 220
    const val CHAT_INPUT_X = 380
    const val CHAT_INPUT_Y = 895
    const val SEND_X = 920
    const val SEND_Y = 895

    fun parseIntent(command: String): Intent? {
        val text = command.trim()
        if (text.isBlank()) return null

        openAppAndSend.find(text)?.let { match ->
            val app = normalizeAppName(match.groupValues[1])
            val contact = cleanLabel(match.groupValues[2])
            val message = cleanLabel(match.groupValues[3])
            if (app.isNotBlank() && contact.isNotBlank() && message.isNotBlank()) {
                return Intent(contact = contact, message = message, appName = app)
            }
        }

        sendViaNamedApp.find(text)?.let { match ->
            val contact = cleanLabel(match.groupValues[1])
            val message = cleanLabel(match.groupValues[2])
            if (contact.isNotBlank() && message.isNotBlank()) {
                val app = if (text.contains("qq", ignoreCase = true)) "QQ" else DEFAULT_IM_APP
                return Intent(contact = contact, message = message, appName = app)
            }
        }

        sendToPerson.find(text)?.let { match ->
            val contact = cleanLabel(match.groupValues[2])
            val message = cleanLabel(match.groupValues[4])
            if (contact.isNotBlank() && message.isNotBlank()) {
                return Intent(contact = contact, message = message)
            }
        }

        return null
    }

    fun stepsUntilChatPage(intent: Intent): List<AgentAction> =
        stepsUntilChatPage(intent.contact, intent.appName)

    fun stepsUntilChatPage(contact: String, appName: String = DEFAULT_IM_APP): List<AgentAction> = listOf(
        AgentAction(action = "open_app", targetText = appName),
        AgentAction(action = "wait"),
        AgentAction(action = "tap", targetText = coords(SEARCH_X, SEARCH_Y)),
        AgentAction(
            action = "type",
            targetText = coords(SEARCH_X, SEARCH_Y),
            inputText = contact,
        ),
        AgentAction(action = "tap", targetText = coords(FIRST_RESULT_X, FIRST_RESULT_Y)),
        AgentAction(action = "wait"),
    )

    fun stepsOnChatPage(message: String, appName: String = DEFAULT_IM_APP): List<AgentAction> = listOf(
        AgentAction(action = "tap", targetText = coords(CHAT_INPUT_X, CHAT_INPUT_Y)),
        AgentAction(
            action = "type",
            targetText = coords(CHAT_INPUT_X, CHAT_INPUT_Y),
            inputText = message,
        ),
        AgentAction(action = "send", targetText = coords(SEND_X, SEND_Y)),
        AgentAction(
            action = "finish",
            message = "已尝试通过$appName 发送：$message",
            finished = true,
        ),
    )

    fun allSteps(intent: Intent): List<AgentAction> =
        stepsUntilChatPage(intent) + stepsOnChatPage(intent.message, intent.appName)

    fun drainNextSteps(intent: Intent, stepRecords: List<AgentStepRecord>): List<AgentAction>? {
        if (playbookCompleted(stepRecords, intent.appName)) return null
        return when (inferPhase(stepRecords, intent.appName)) {
            Phase.NAVIGATE -> stepsUntilChatPage(intent)
            Phase.ON_CHAT -> stepsOnChatPage(intent.message, intent.appName)
            Phase.DONE -> null
        }
    }

    private enum class Phase {
        NAVIGATE,
        ON_CHAT,
        DONE,
    }

    private fun inferPhase(stepRecords: List<AgentStepRecord>, appName: String): Phase {
        if (playbookCompleted(stepRecords, appName)) return Phase.DONE
        if (navigationCompleted(stepRecords, appName)) return Phase.ON_CHAT
        return Phase.NAVIGATE
    }

    private fun navigationCompleted(records: List<AgentStepRecord>, appName: String): Boolean {
        val openedApp = records.any {
            it.result.success &&
                it.action.action.equals("open_app", ignoreCase = true) &&
                it.action.targetText?.contains(appName, ignoreCase = true) == true
        }
        val typedContact = records.any {
            it.result.success && it.action.action.equals("type", ignoreCase = true)
        }
        val openedChat = records.any {
            it.result.success &&
                it.action.action.equals("tap", ignoreCase = true) &&
                it.action.targetText == coords(FIRST_RESULT_X, FIRST_RESULT_Y)
        }
        return openedApp && typedContact && openedChat
    }

    private fun playbookCompleted(records: List<AgentStepRecord>, appName: String): Boolean {
        val finishMarker = "已尝试通过$appName 发送"
        return records.any {
            it.action.action.equals("finish", ignoreCase = true) &&
                it.result.success &&
                (it.action.message?.contains(finishMarker) == true ||
                    it.action.message?.contains("已尝试发送") == true)
        }
    }

    private fun normalizeAppName(raw: String): String {
        val cleaned = cleanLabel(raw)
        return when {
            cleaned.contains("qq", ignoreCase = true) -> "QQ"
            cleaned.contains("微信") -> DEFAULT_IM_APP
            cleaned.isBlank() -> ""
            else -> cleaned
        }
    }

    private fun coords(x: Int, y: Int): String = "$x,$y"

    private fun cleanLabel(raw: String): String =
        raw.trim()
            .removePrefix("给")
            .removePrefix("向")
            .removeSuffix("的")
            .trim()
}
