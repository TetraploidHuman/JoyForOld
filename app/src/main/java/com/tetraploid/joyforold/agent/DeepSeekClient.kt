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
        minimalPageContext: String,
        pageContextMode: PageContextMode = PageContextMode.FULL,
    ): JSONObject {
        conversation.seedSystem(buildSystemPrompt(keyMemories))
        conversation.addUser(
            buildString {
                appendLine("【用户指令】$userCommand")
                append(
                    AgentMessageCompactor.formatPageSection(
                        pageContext = pageContext,
                        pageDiff = pageDiff,
                        minimalPageContext = minimalPageContext,
                        mode = pageContextMode,
                    ),
                )
                appendLine()
                appendLine(
                    "请规划本屏接下来 1~${AgentPlanParser.MAX_PLANNED_STEPS} 步（高置信度、同一屏连续操作）。" +
                        "返回 JSON：{\"actions\":[...]}；单步也可用 {\"action\":...}。" +
                        "open_app/list_apps/finish/read_tree/send 必须单独一步，禁止超过 ${AgentPlanParser.MAX_PLANNED_STEPS} 步。",
                )
            },
        )

        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 DeepSeek API Key")
            val body = baseRequestBody().apply {
                put("max_tokens", 512)
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
        minimalPageContext: String = "",
        pageContextMode: PageContextMode = PageContextMode.FULL,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 DeepSeek API Key")

        ensureSystemSeeded(conversation, keyMemories)

        conversation.addUser(
            buildString {
                appendLine(stepFeedback)
                append(
                    AgentMessageCompactor.formatPageSection(
                        pageContext = pageContext,
                        pageDiff = pageDiff,
                        minimalPageContext = minimalPageContext,
                        mode = pageContextMode,
                    ),
                )
                appendLine()
                appendLine(
                    "请规划本屏接下来 1~${AgentPlanParser.MAX_PLANNED_STEPS} 步（高置信度、同一屏连续操作）。" +
                        "返回 JSON：{\"actions\":[...]} 或单步 {\"action\":...}。" +
                        "open_app/list_apps/finish/read_tree/send 必须单独一步，禁止超过 ${AgentPlanParser.MAX_PLANNED_STEPS} 步。",
                )
            },
        )

        val body = baseRequestBody().apply {
            put("max_tokens", 512)
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
                            "从手机助手任务记录中提取一条可复用的用户偏好/联系人信息（1句话，30字内），" +
                                "例如常用联系人、默认打电话方式、常用应用。不要复述本次具体任务步骤。" +
                                "若无可复用信息，只返回空字符串。只返回纯文本，不要 JSON。",
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

    fun ensureSystemSeeded(
        conversation: AgentConversationSession,
        keyMemories: String,
    ) {
        if (conversation.hasSystem()) return
        conversation.seedSystem(buildSystemPrompt(keyMemories))
    }

    suspend fun classifyPresetIntent(
        apiKey: String,
        utterance: String,
    ): Pair<String, Double>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return@withContext null

        val body = baseRequestBody().apply {
            put("max_tokens", 60)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().put("role", "system").put(
                            "content",
                            """
                            你是老年手机助手的「指令分类器」。
                            任务：只从下列意图中选择一项（或 none），并给出 0~1 置信度：
                            - navigate_home: 用户希望回到家/住所/家里/屋里
                            - ask_family_for_help: 用户希望联系家人帮忙，但不是紧急求救
                            - emergency_help: 用户有强烈紧急求助/危险信号
                            - open_payment_code: 用户希望打开付款码/收款码
                            - open_health_code: 用户希望打开健康码/健康码相关页面
                            - none: 不属于以上任何意图

                            严格按照下列 JSON 返回，不要输出多余文字：
                            {"intent":"navigate_home|ask_family_for_help|emergency_help|open_payment_code|open_health_code|none","confidence":0.0~1.0}
                            """.trimIndent(),
                        ),
                    )
                    put(
                        JSONObject().put("role", "user").put(
                            "content",
                            "用户原话：$trimmed",
                        ),
                    )
                },
            )
        }

        val content = try {
            postChatRaw(apiKey, body)
        } catch (_: Exception) {
            return@withContext null
        }

        val json = runCatching { JSONObject(content) }.getOrNull() ?: return@withContext null
        val intent = json.optString("intent").ifBlank { "none" }
        val confidence = json.optDouble("confidence", 0.0)
        intent to confidence
    }

    private fun buildSystemPrompt(keyMemories: String): String = """
        你是手机操作 Agent，工作方式类似 Claude Code / Codex：观察页面 → 选一步工具 → 看结果 → 再观察。
        ${AgentToolRegistry.descriptionsForPrompt()}

        【历史关键记忆（仅供参考）】
        $keyMemories

        【原则】
        - **必须以本轮【用户指令】为唯一目标**；历史记忆只能辅助，禁止擅自继续上一轮未提及的任务。
        - 每次返回 **actions 数组（1~${AgentPlanParser.MAX_PLANNED_STEPS} 步）** 或单步 action；**action 必须在工具白名单内**，只允许：${AgentToolRegistry.toolNames.joinToString()}；基于页面快览和变化决策，禁止让用户描述页面。
        - **同一屏内**才可规划 2 步；open_app、list_apps、finish、read_tree、send、拨号/发短信必须 **单独 1 步**。
        - 能走系统级动作时优先走系统动作（dial_contact/send_sms/set_alarm/add_calendar_event/open_*），避免纯 UI 点按。
        - 找联系人：优先可见列表模糊匹配（同音字、谐音、号码片段），直接 click；找不到先 scroll_down 或 swipe_down。
        - 需要切换应用时：不确定应用名先 list_apps（可带 target_text 筛选），再用 open_app；target_text **必须**与 list_apps 返回的名称逐字一致，禁止猜测。
        - 若 open_app 失败，先 list_apps 核对名称，或根据失败提示中的「你可能想找」换用准确名称，不要重复同一错误名称。
        - 不确定时用 find_on_page 搜索；结构复杂用 read_tree。
        - **完成判定（通用）**：
          · finish 前必须用【当前页面快览】和【页面变化】验证用户目标已达成；message **不得描述页面上看不到的状态**
          · type / find_on_page 通常只是中间步骤；若用户要对某对象进行操作（听/看/打开/选/买/发等），还必须 click 目标项或对应按钮
          · open_app 后继续在应用内操作，不要打开就立刻 finish
          · 若不确定是否完成，用 find_on_page 或 read_tree 确认，或 finish+waiting_for_user 询问用户
        - **上一步失败后禁止重复相同操作**；必须换策略（搜索/滚动/读树/换应用/询问用户）。
        - **敏感操作必须先询问用户**（finish + waiting_for_user:true）：
          · 拨打电话、点击拨打/通话按钮 → needs_binary_confirm:true
          · 发送消息（send 或点击发送）→ needs_binary_confirm:true
          · 给指定联系人发消息输入完成后 → needs_binary_confirm:true
          · 联系人歧义、QQ电话 vs 手机电话未明确 → needs_binary_confirm:false（开放问答）
        - **闲聊/问候/说明性回复**：finished:true, waiting_for_user:false；不要因为 message 带问号就设 waiting_for_user。
        - 任务完成：finish, finished:true；需用户回复：waiting_for_user:true（并按上文设置 needs_binary_confirm）。
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
