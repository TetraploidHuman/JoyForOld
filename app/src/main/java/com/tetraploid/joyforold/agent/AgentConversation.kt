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
    rootCommand: String,
    messages: List<ChatMessage> = emptyList(),
    stepRecords: List<AgentStepRecord> = emptyList(),
    answeredConfirmPrompts: Set<String> = emptySet(),
    resolvedConfirmTopics: Set<String> = emptySet(),
) {
    private val messages = messages.toMutableList()
    val stepRecords = stepRecords.toMutableList()
    var rootCommand: String = rootCommand
    private val answeredConfirmPrompts = answeredConfirmPrompts.toMutableSet()
    private val resolvedConfirmTopics = resolvedConfirmTopics.toMutableSet()

    /** 本会话累计 prompt tokens（来自 API usage，缺省为 0） */
    var promptTokensTotal: Int = 0
        private set
    var completionTokensTotal: Int = 0
        private set
    val totalTokensTotal: Int
        get() = promptTokensTotal + completionTokensTotal

    fun addTokenUsage(usage: LlmApiSupport.TokenUsage) {
        usage.promptTokens?.let { promptTokensTotal += it }
        usage.completionTokens?.let { completionTokensTotal += it }
        // 若只有 total、没有分项，计入 prompt 侧以免丢总量（少见）
        if (usage.promptTokens == null && usage.completionTokens == null) {
            usage.totalTokens?.let { promptTokensTotal += it }
        }
    }

    fun hasAnsweredConfirmPrompt(prompt: String): Boolean {
        return answeredConfirmPrompts.contains(prompt.trim())
    }

    fun hasResolvedConfirmTopic(topic: String): Boolean {
        return topic in resolvedConfirmTopics
    }

    /** 记录用户对确认问题的完整原话，供 AI 与守卫续跑使用 */
    fun recordConfirmAnswer(aiPrompt: String, userReply: String) {
        val prompt = aiPrompt.trim()
        val reply = userReply.trim()
        if (prompt.isNotBlank()) {
            answeredConfirmPrompts += prompt
            inferConfirmTopic(prompt)?.let { resolvedConfirmTopics += it }
        }
        if (reply.isNotBlank()) {
            addUser(
                buildString {
                    appendLine("【用户回答确认】")
                    if (prompt.isNotBlank()) appendLine("问题：$prompt")
                    appendLine("回答：$reply")
                }.trim(),
            )
            rootCommand = if (rootCommand.isBlank()) {
                reply
            } else {
                "${rootCommand.trim()}\n[用户回答] $reply"
            }
        }
    }

    private fun inferConfirmTopic(aiPrompt: String): String? {
        return when {
            aiPrompt.contains("打电话") || aiPrompt.contains("QQ电话") || aiPrompt.contains("手机电话") ->
                CONFIRM_TOPIC_CALL_ROUTE
            aiPrompt.contains("发送") || aiPrompt.contains("取消") ->
                CONFIRM_TOPIC_SEND
            else -> null
        }
    }
    var status: String = "running"
    var finalSummary: String = ""

    fun seedSystem(systemPrompt: String) {
        if (this.messages.any { it.role == "system" }) return
        this.messages += ChatMessage("system", systemPrompt)
    }

    /** 进入视觉模式时刷新 system，注入 tap/坐标规则 */
    fun refreshSystem(systemPrompt: String) {
        val index = messages.indexOfFirst { it.role == "system" }
        if (index >= 0) {
            messages[index] = ChatMessage("system", systemPrompt)
        } else {
            messages.add(0, ChatMessage("system", systemPrompt))
        }
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
    fun pruneForApi(maxTailMessages: Int = 14) {
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

    fun toApiMessages(latestScreenshotBase64: String? = null): JSONArray {
        pruneForApi()
        val apiMessages = AgentMessageCompactor.compactForApi(messages)
        return JSONArray().apply {
            apiMessages.forEachIndexed { index, msg ->
                val isLatestUser = index == apiMessages.lastIndex &&
                    msg.role == "user" &&
                    !latestScreenshotBase64.isNullOrBlank()
                if (isLatestUser) {
                    put(LlmMultimodalMessage.userMessage(msg.content, latestScreenshotBase64))
                } else {
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
        }
    }

    fun systemInstructions(): String =
        messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }

    /** Volc Responses API：system 走 instructions，input 仅含 user/assistant 轮次 */
    fun toResponsesApiInput(latestScreenshotBase64: String? = null): JSONArray {
        pruneForApi()
        val apiMessages = AgentMessageCompactor.compactForApi(messages.filter { it.role != "system" })
        return JSONArray().apply {
            apiMessages.forEachIndexed { index, msg ->
                val isLatestUser = index == apiMessages.lastIndex &&
                    msg.role == "user" &&
                    !latestScreenshotBase64.isNullOrBlank()
                when {
                    isLatestUser ->
                        put(LlmMultimodalMessage.responsesUserMessage(msg.content, latestScreenshotBase64))
                    msg.role == "assistant" ->
                        put(JSONObject().put("role", "assistant").put("content", msg.content))
                    else ->
                        put(JSONObject().put("role", "user").put("content", msg.content))
                }
            }
        }
    }

    fun buildSessionSummary(): String = buildString {
        appendLine("指令：$rootCommand")
        appendLine("状态：$status")
        if (finalSummary.isNotBlank()) appendLine("结果：$finalSummary")
        if (promptTokensTotal > 0 || completionTokensTotal > 0) {
            appendLine(
                "Token：prompt=$promptTokensTotal completion=$completionTokensTotal total=$totalTokensTotal",
            )
        }
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
            "answered_confirm_prompts",
            JSONArray().apply { answeredConfirmPrompts.forEach { put(it) } },
        )
        put(
            "resolved_confirm_topics",
            JSONArray().apply { resolvedConfirmTopics.forEach { put(it) } },
        )
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
        const val CONFIRM_TOPIC_CALL_ROUTE = "call_route"
        const val CONFIRM_TOPIC_SEND = "send"

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
                answeredConfirmPrompts = json.optJSONArray("answered_confirm_prompts")
                    ?.let { arr -> buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) } }
                    .orEmpty(),
                resolvedConfirmTopics = json.optJSONArray("resolved_confirm_topics")
                    ?.let { arr -> buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) } }
                    .orEmpty(),
            ).apply {
                status = json.optString("status", "running")
                finalSummary = json.optString("final_summary", "")
            }
        }
    }
}
