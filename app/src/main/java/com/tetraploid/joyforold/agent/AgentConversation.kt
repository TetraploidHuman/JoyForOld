package com.tetraploid.joyforold.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val role: String,
    val content: String,
)

data class AgentStepRecord(
    val step: Int,
    val action: AgentAction,
    val result: ActionExecutionResult,
    val pageDiff: String,
)

class AgentConversationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val rootCommand: String,
) {
    private val messages = mutableListOf<ChatMessage>()
    val stepRecords = mutableListOf<AgentStepRecord>()
    var status: String = "running"
    var finalSummary: String = ""

    fun seedSystem(systemPrompt: String) {
        if (messages.any { it.role == "system" }) return
        messages += ChatMessage("system", systemPrompt)
    }

    fun addUser(content: String) {
        messages += ChatMessage("user", content)
    }

    fun addAssistant(content: String) {
        messages += ChatMessage("assistant", content)
    }

    fun recordStep(step: Int, action: AgentAction, result: ActionExecutionResult, pageDiff: String) {
        stepRecords += AgentStepRecord(step, action, result, pageDiff)
    }

    fun toApiMessages(): JSONArray {
        return JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }
    }

    fun buildSessionSummary(): String = buildString {
        appendLine("指令：$rootCommand")
        appendLine("状态：$status")
        if (finalSummary.isNotBlank()) appendLine("结果：$finalSummary")
        stepRecords.takeLast(20).forEach { record ->
            val target = record.action.targetText?.let { " target=$it" }.orEmpty()
            val input = record.action.inputText?.let { " input=$it" }.orEmpty()
            appendLine(
                "步骤${record.step}: ${record.action.action}$target$input → " +
                    if (record.result.success) "成功" else "失败",
            )
        }
    }.trim()

    fun messageCount(): Int = messages.size
}
