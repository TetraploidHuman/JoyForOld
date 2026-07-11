package com.tetraploid.joyforold.agent

object PresetIntentResolver {
    private const val MIN_CONFIDENCE = 0.7

    suspend fun resolve(
        command: String,
        apiKey: String,
        client: DeepSeekClient,
    ): List<AgentAction>? {
        return resolveWithConfidence(command, apiKey, client)?.first
    }

    suspend fun resolveWithConfidence(
        command: String,
        apiKey: String,
        client: DeepSeekClient,
    ): Pair<List<AgentAction>, Double>? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null

        val (intent, confidence) = client.classifyPresetIntent(apiKey, trimmed) ?: return null
        if (confidence < MIN_CONFIDENCE) return null

        val steps = stepsForIntent(intent) ?: return null
        return steps to confidence
    }

    fun stepsForIntent(intent: String): List<AgentAction>? {
        return when (intent) {
            "navigate_home" -> listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "导航回家", finished = true),
            )
            "ask_family_for_help" -> listOf(
                AgentAction(action = "ask_family_for_help"),
                AgentAction(action = "finish", message = "向家人发求助消息", finished = true),
            )
            "emergency_help" -> listOf(
                AgentAction(action = "emergency_help"),
                AgentAction(action = "finish", message = "执行紧急呼救", finished = true),
            )
            "open_payment_code" -> listOf(
                AgentAction(action = "open_payment_code"),
                AgentAction(action = "finish", message = "打开付款码", finished = true),
            )
            "open_health_code" -> listOf(
                AgentAction(action = "open_health_code"),
                AgentAction(action = "finish", message = "打开健康码", finished = true),
            )
            else -> null
        }
    }
}
