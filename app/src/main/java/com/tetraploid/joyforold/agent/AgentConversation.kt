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
    messages: List<ChatMessage> = emptyList(),
    stepRecords: List<AgentStepRecord> = emptyList(),
) {
    private val messages = messages.toMutableList()
    val stepRecords = stepRecords.toMutableList()
    var status: String = "running"
    var finalSummary: String = ""

    fun seedSystem(systemPrompt: String) {
        if (this.messages.any { it.role == "system" }) return
        this.messages += ChatMessage("system", systemPrompt)
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

    /** 控制上下文长度：保留 system、首条用户指令、最近对话轮次 */
    fun pruneForApi(maxTailMessages: Int = 22) {
        if (messages.size <= maxTailMessages + 2) return

        val systemMsgs = messages.filter { it.role == "system" }
        val firstUserIdx = messages.indexOfFirst { msg ->
            msg.role == "user" && msg.content.contains("【用户指令】")
        }
        val firstUserCommand = if (firstUserIdx >= 0) messages[firstUserIdx] else null
        val tail = messages.filterIndexed { idx, msg ->
            msg.role != "system" && idx != firstUserIdx
        }.takeLast(maxTailMessages)

        messages.clear()
        messages.addAll(systemMsgs)
        if (firstUserCommand != null) messages += firstUserCommand
        if (tail.isNotEmpty()) {
            if (firstUserCommand != null && messages.size > 1) {
                messages += ChatMessage("user", "（此前若干步对话已压缩，请结合近期记录继续）")
            }
            messages.addAll(tail)
        }
    }

    fun toApiMessages(): JSONArray {
        pruneForApi()
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

    fun hasSystem(): Boolean = messages.any { it.role == "system" }

    fun appendLocalStepsSummary(logs: List<AgentStepLog>) {
        if (logs.isEmpty()) return
        addUser(
            buildString {
                appendLine("【本地快路径已执行】")
                logs.forEach { log ->
                    appendLine("步骤${log.step}: ${log.action.action} → ${if (log.success) "成功" else "失败"}")
                }
            }.trim(),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("root_command", rootCommand)
        put("status", status)
        put("final_summary", finalSummary)
        put(
            "messages",
            JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            },
        )
        put(
            "step_records",
            JSONArray().apply {
                stepRecords.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("step", record.step)
                            put("action", record.action.toJson())
                            put("result_success", record.result.success)
                            put("result_summary", record.result.summary)
                            put("page_diff", record.pageDiff)
                        },
                    )
                }
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): AgentConversationSession {
            val messages = json.optJSONArray("messages")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(ChatMessage(obj.getString("role"), obj.getString("content")))
                    }
                }
            }.orEmpty()

            val stepRecords = json.optJSONArray("step_records")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            AgentStepRecord(
                                step = obj.getInt("step"),
                                action = AgentAction.fromJson(obj.getJSONObject("action")),
                                result = ActionExecutionResult(
                                    success = obj.optBoolean("result_success"),
                                    summary = obj.optString("result_summary"),
                                ),
                                pageDiff = obj.optString("page_diff"),
                            ),
                        )
                    }
                }
            }.orEmpty()

            return AgentConversationSession(
                sessionId = json.optString("session_id", UUID.randomUUID().toString()),
                rootCommand = json.optString("root_command"),
                messages = messages,
                stepRecords = stepRecords,
            ).apply {
                status = json.optString("status", "running")
                finalSummary = json.optString("final_summary", "")
            }
        }
    }
}
