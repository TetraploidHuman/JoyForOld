package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeepSeekClient(
    private val httpClient: OkHttpClient = sharedClient,
) {
    suspend fun beginTask(
        apiKey: String,
        conversation: AgentConversationSession,
        userCommand: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
    ): JSONObject {
        val systemPrompt = buildSystemPrompt(keyMemories)
        conversation.seedSystem(systemPrompt)
        conversation.addUser(
            buildString {
                appendLine("【用户指令】$userCommand")
                appendLine()
                appendLine("【当前页面快览】")
                append(pageContext)
                appendLine()
                appendLine("【页面变化】")
                append(pageDiff)
                appendLine()
                appendLine("请决定第一步操作，只返回 JSON。")
            },
        )

        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 DeepSeek API Key")
            val body = baseRequestBody().apply {
                put("max_tokens", 384)
                put("messages", conversation.toApiMessages())
            }
            val content = postChatRaw(apiKey, body)
            conversation.addAssistant(content)
            parseJsonObject(content)
        }
    }

    suspend fun continueAfterStep(
        apiKey: String,
        conversation: AgentConversationSession,
        stepFeedback: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String = "",
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 DeepSeek API Key")

        ensureSystemSeeded(conversation, keyMemories)

        conversation.addUser(
            buildString {
                appendLine(stepFeedback)
                appendLine()
                appendLine("【当前页面快览】")
                append(pageContext)
                appendLine()
                appendLine("【页面变化】")
                append(pageDiff)
                appendLine()
                appendLine("请决定下一步，只返回 JSON。")
            },
        )

        val body = baseRequestBody().apply {
            put("max_tokens", 384)
            put("messages", conversation.toApiMessages())
        }
        val content = postChatRaw(apiKey, body)
        conversation.addAssistant(content)
        parseJsonObject(content)
    }

    suspend fun extractKeyMemory(
        apiKey: String,
        sessionSummary: String,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || sessionSummary.isBlank()) return@withContext ""

        val body = baseRequestBody().apply {
            put("max_tokens", 120)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().put("role", "system").put(
                            "content",
                            "从手机助手任务记录中提取一条简短关键记忆（1句话，30字内），供以后类似任务参考。只返回纯文本，不要 JSON。",
                        ),
                    )
                    put(JSONObject().put("role", "user").put("content", sessionSummary))
                },
            )
        }

        try {
            postChatRaw(apiKey, body).trim().take(120)
        } catch (_: Exception) {
            sessionSummary.lineSequence().firstOrNull { it.startsWith("指令：") }
                ?.removePrefix("指令：")
                ?.trim()
                ?.take(40)
                .orEmpty()
        }
    }

    fun ensureSystemSeeded(conversation: AgentConversationSession, keyMemories: String) {
        if (conversation.hasSystem()) return
        conversation.seedSystem(buildSystemPrompt(keyMemories))
    }

    private fun buildSystemPrompt(keyMemories: String): String = """
        你是手机操作 Agent，工作方式类似 Claude Code / Codex：观察页面 → 选一步工具 → 看结果 → 再观察。
        ${AgentToolRegistry.descriptionsForPrompt()}

        【历史关键记忆】
        $keyMemories

        【原则】
        - 每次只输出一个 action；基于页面快览和变化决策，禁止让用户描述页面。
        - 找联系人：优先可见列表模糊匹配（同音字、谐音、号码片段），直接 click；找不到先 scroll_down 或 swipe_down。
        - 需要切换应用时用 open_app，且 target_text 只能填：QQ、微信、电话、联系人、短信、设置（禁止打开其他包名或系统组件）。
        - 不确定时用 find_on_page 搜索；结构复杂用 read_tree。
        - **上一步失败后禁止重复相同操作**；必须换策略（搜索/滚动/读树/换应用/询问用户）。
        - **敏感操作必须先询问用户**（finish + waiting_for_user:true）：
          · 拨打电话、点击拨打/通话按钮
          · 发送消息（send 或点击发送）
          · 给指定联系人发消息输入完成后
          · 联系人歧义、QQ电话 vs 手机电话未明确
        - 任务完成：finish, finished:true；需用户回复：waiting_for_user:true。
    """.trimIndent()

    private fun baseRequestBody(): JSONObject {
        return JSONObject().apply {
            put("model", BuildConfig.DEEPSEEK_MODEL)
            put("temperature", 0.2)
            put("stream", false)
            put("thinking", JSONObject().put("type", "disabled"))
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    private suspend fun postChatRaw(apiKey: String, body: JSONObject): String {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return postChatRawOnce(apiKey, body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (!isRetryable(error) || attempt == MAX_RETRIES - 1) throw error
                delay(RETRY_BASE_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("DeepSeek 请求失败")
    }

    private suspend fun postChatRawOnce(apiKey: String, body: JSONObject): String {
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            try {
                call.execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        continuation.resumeWithException(
                            IllegalStateException(
                                "DeepSeek 请求失败 (${response.code}): ${responseBody.take(500)}",
                            ),
                        )
                        return@suspendCancellableCoroutine
                    }
                    continuation.resume(extractAssistantContent(responseBody))
                }
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun isRetryable(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("(429)") ||
            message.contains("(500)") ||
            message.contains("(502)") ||
            message.contains("(503)") ||
            error is IOException
    }

    private fun extractAssistantContent(responseBody: String): String {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("API 响应缺少 choices：${responseBody.take(300)}")

        if (choices.length() == 0) {
            throw IllegalStateException("API choices 为空")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("API 响应缺少 message")

        val content = message.optString("content", "").trim()
        if (content.isNotBlank()) return unwrapJsonFence(content)

        val reasoning = message.optString("reasoning_content", "").trim()
        if (reasoning.isNotBlank()) return unwrapJsonFence(reasoning)

        throw IllegalStateException("API 返回空 content")
    }

    private fun unwrapJsonFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseJsonObject(text: String): JSONObject {
        if (text.isBlank()) throw IllegalStateException("JSON 内容为空")
        return try {
            JSONObject(text)
        } catch (first: Exception) {
            val extracted = extractJsonObjectCandidate(text)
            if (extracted != null) {
                return JSONObject(cleanupLooseJson(extracted))
            }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) {
                return JSONObject(cleanupLooseJson(text.substring(start, end + 1)))
            }
            throw IllegalStateException("JSON 解析失败：${first.message}", first)
        }
    }

    private fun extractJsonObjectCandidate(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun cleanupLooseJson(text: String): String {
        return text.replace(Regex(",\\s*([}\\]])"), "$1")
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 600L

        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
