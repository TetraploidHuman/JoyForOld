package com.tetraploid.joyforold.agent

object PresetIntentResolver {
    private const val MIN_CONFIDENCE = 0.7

    suspend fun resolve(
        command: String,
        apiKey: String,
        client: DeepSeekClient,
    ): List<AgentAction>? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null

        val (intent, confidence) = client.classifyPresetIntent(apiKey, trimmed) ?: return null
        if (confidence < MIN_CONFIDENCE) return null

        return when (intent) {
            "navigate_home" -> listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "正在为您导航回家。", finished = true),
            )
            "ask_family_for_help" -> listOf(
                AgentAction(action = "ask_family_for_help"),
                AgentAction(action = "finish", message = "已准备向家人发求助消息。", finished = true),
            )
            "emergency_help" -> listOf(
                AgentAction(action = "emergency_help"),
                AgentAction(action = "finish", message = "已执行紧急呼救流程。", finished = true),
            )
            "open_payment_code" -> listOf(
                AgentAction(action = "open_payment_code"),
                AgentAction(action = "finish", message = "已尝试打开付款码入口。", finished = true),
            )
            "open_health_code" -> listOf(
                AgentAction(action = "open_health_code"),
                AgentAction(action = "finish", message = "已尝试打开健康码入口。", finished = true),
            )
            else -> null
        }
    }
}

