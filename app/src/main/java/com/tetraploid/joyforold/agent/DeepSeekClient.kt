package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepSeekClient(
    private val httpClient: OkHttpClient = sharedClient,
) {
    suspend fun planBatch(
        userCommand: String,
        pageContext: String,
        apiKey: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("请先填写 DeepSeek API Key")
        }

        val systemPrompt = """
            你是手机操作助手。只返回 JSON，一次性给出完整步骤数组。

            【最高优先级 - 页面上下文与联系人】
            - 系统已附带当前页面快览，你必须先读快览再规划，禁止忽略页面元素。
            - 找联系人/拨号对象时：优先在**当前页面可见列表**里模糊匹配（同音字、谐音、昵称片段、号码片段如"610"），直接 click 页面上最像的目标。
            - **禁止**第一步就去点「搜索」或在搜索框 type 关键词，除非快览里明确有搜索框且当前列表里确实找不到任何相似联系人。
            - 列表中未看到目标时，先 scroll_down 浏览；仍找不到再用 finish+waiting_for_user 问用户「请说联系人全名或再描述一下」。
            - 多个候选都很像时，用 finish+waiting_for_user 让用户选择，不要瞎点。

            【最高优先级 - 打电话】
            - 用户说「给XX打电话/打一个电话/拨号」等，若未明确说 QQ电话/QQ语音 或 手机电话/系统拨号：
              - 必须且只能返回一步：{"action":"finish","message":"你要在哪里打电话？请说 QQ电话 或 手机电话","waiting_for_user":true,"finished":true}
              - 在用户回答前，禁止输出 click/type/back 等任何操作步骤。
            - 禁止要求用户「打开电话/联系人应用」「告诉我当前页面内容」——系统已自动提供页面快览，你直接基于快览规划；若快览为空，用 home/click 等步骤自行导航，不要用 finish 让用户描述页面。
            - 用户已明确 QQ电话 → 在 QQ/聊天里找语音通话；已明确手机电话 → 用系统拨号盘。

            【通用规则】
            - 如果用户需要很多连续操作（例如给同一个人发 10 条消息），可以拆成多条指令的批量步骤，只要保持顺序正确即可。
            - 每次操作都必须以“当前页面真实可见元素”为依据：如果当前页没有目标元素，不要硬点，先返回/切换页面，再读取新页面元素后继续。
            - 这个规则适用于所有场景，不仅是聊天：包括设置、联系人、拨号、短信、系统页面、多页面跳转等。
            - 语音识别可能把联系人姓名识别成同音字、近音字或数字谐音，例如“刘一麟”识别成“六一林”。如果当前页面是联系人列表/聊天列表，你要优先结合页面上真实可见的联系人名称，选择最像、最可能的目标联系人，而不是死按字面精确匹配。
            - 如果当前页面已经是某个聊天页面，而用户要求“给另一个联系人发消息/发短信/打电话”，你应该先 back 返回到联系人/会话列表，再根据返回后的页面信息去寻找目标联系人，不能默认当前聊天对象就是目标。
            - 当用户说“给某人发消息”这类指令时，你应该只负责：进入对应联系人/会话、把消息内容输入到输入框，但**不要主动发送**，最后用 finish 步骤给出类似“已为 XX 准备好消息：YYY，请用户确认后再说：发送上一条消息”的提示，并设置 waiting_for_user:true，把“是否发送”的最终决策留给用户。
            - 当用户要求“打语音电话/语音通话/拨号/呼叫/视频通话”且用户已选定方式后：
                1) 你需要先在当前页面快览里找是否已经可见对应“语音通话/电话/拨号/呼叫”按钮；
                2) 如果当前页面快览里看不到明显的“语音通话/电话”按钮，你应先探索折叠菜单/更多按钮：优先点击“更多”“...” “菜单”“更多选项”等入口按钮以展开选项；
                3) 展开后再根据新的可见元素快览继续寻找并点击“语音通话/电话/拨号/呼叫”按钮。
            - 当你需要用户确认、选择或补充信息时（例如：拨号前确认、发短信前确认、联系人歧义），必须用 finish 步骤提问，并设置 waiting_for_user:true；message 写清楚用户该说什么。
            - 例如准备拨号前：{"action":"finish","message":"已找到拨号入口，请说「确认拨号」继续","waiting_for_user":true,"finished":true}
            - 每个步骤的 action 只能是 click|type|send|scroll_down|scroll_up|back|home|wait|finish。
            格式：
            {"steps":[{"action":"click","target_text":"张三"},{"action":"type","input_text":"你好"},{"action":"send"}],"finished":true,"message":"已发送"}
            finish 步骤可选字段：waiting_for_user (boolean)，为 true 表示暂停并等待用户语音/文字回复后再继续。
            重要：用户要求发消息/发送时，步骤必须是 输入(type) 后 再 send 或 click发送，禁止只 type 就 finish。
            发消息示例：{"steps":[{"action":"type","input_text":"你好"},{"action":"send"}],"finished":true,"message":"已发送"}
        """.trimIndent()

        val body = baseRequestBody().apply {
            // 多条指令时步骤较多，适当放宽返回长度
            put("max_tokens", 768)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(
                        JSONObject().put("role", "user").put("content", "指令：$userCommand\n\n$pageContext"),
                    )
                },
            )
        }

        postChat(apiKey, body)
    }

    suspend fun planAfterUserReply(
        originalCommand: String,
        assistantPrompt: String,
        userAnswer: String,
        pageContext: String,
        apiKey: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("请先填写 DeepSeek API Key")
        }

        val systemPrompt = """
            你是手机操作助手。用户此前在执行任务时被助手提问，现在已回答。请结合原指令、助手问题和用户回答，基于当前页面快览继续规划。
            - 用户已回答助手问题，不要重复提问（除非页面信息仍不足以安全操作）。
            - 必须优先使用当前页面快览里的可见元素；找联系人时模糊匹配同音字/谐音/昵称片段/号码片段，不要死按 ASR 字面去搜索。
            - 禁止第一步就 type 搜索关键词，除非当前列表确实没有任何相似联系人且快览里有搜索框。
            - 只返回 JSON 步骤数组，格式与 planBatch 相同。
        """.trimIndent()

        val body = baseRequestBody().apply {
            put("max_tokens", 768)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(
                        JSONObject().put("role", "user").put(
                            "content",
                            """
                            原指令：$originalCommand
                            助手询问：$assistantPrompt
                            用户回答：$userAnswer

                            $pageContext
                            """.trimIndent(),
                        ),
                    )
                },
            )
        }

        postChat(apiKey, body)
    }

    suspend fun replanStep(
        userCommand: String,
        pageContext: String,
        apiKey: String,
        lastResult: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val systemPrompt = """
            只返回 JSON：
            {"action":"click|type|scroll_down|scroll_up|back|home|finish","target_text":"","input_text":"","message":"","finished":false,"waiting_for_user":false}
            若需要用户确认/选择/补充信息，用 finish 并设 waiting_for_user:true。
            重规划时必须优先点击当前页面快览里可见的最相似联系人/按钮；不要 type 搜索除非列表里确实没有相似项。
        """.trimIndent()

        val body = baseRequestBody().apply {
            put("max_tokens", 160)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(
                        JSONObject().put("role", "user").put("content",
                            "指令：$userCommand\n上一步：$lastResult\n\n$pageContext",
                        ),
                    )
                },
            )
        }

        postChat(apiKey, body)
    }

    private fun baseRequestBody(): JSONObject {
        return JSONObject().apply {
            put("model", BuildConfig.DEEPSEEK_MODEL)
            put("temperature", 0.2)
            put("stream", false)
            put("thinking", JSONObject().put("type", "disabled"))
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    private fun postChat(apiKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("DeepSeek 请求失败 (${response.code}): ${responseBody.take(500)}")
            }
            if (responseBody.isBlank()) {
                throw IllegalStateException("DeepSeek 返回空响应体")
            }

            val content = extractAssistantContent(responseBody)
            return parseJsonObject(content)
        }
    }

    private fun extractAssistantContent(responseBody: String): String {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("API 响应缺少 choices：${responseBody.take(300)}")

        if (choices.length() == 0) {
            throw IllegalStateException("API choices 为空：${responseBody.take(300)}")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("API 响应缺少 message：${responseBody.take(300)}")

        val content = message.optString("content", "").trim()
        if (content.isNotBlank()) {
            return unwrapJsonFence(content)
        }

        val reasoning = message.optString("reasoning_content", "").trim()
        if (reasoning.isNotBlank()) {
            return unwrapJsonFence(reasoning)
        }

        throw IllegalStateException(
            "API 返回空 content（thinking 模式可能未关闭）。原始响应：${responseBody.take(500)}",
        )
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
        if (text.isBlank()) {
            throw IllegalStateException("JSON 内容为空，无法解析")
        }
        return try {
            JSONObject(text)
        } catch (first: Exception) {
            val extracted = extractJsonObjectCandidate(text)
            if (extracted != null) {
                val cleaned = cleanupLooseJson(extracted)
                try {
                    return JSONObject(cleaned)
                } catch (second: Exception) {
                    throw IllegalStateException(
                        "JSON 解析失败：${second.message}，内容：${extracted.take(240)}",
                        second,
                    )
                }
            }

            // 兜底：用最后一个 '}' 截断（可能截得不准，但尽力）
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    return JSONObject(cleanupLooseJson(text.substring(start, end + 1)))
                } catch (_: Exception) {
                    // ignore and rethrow below
                }
            }

            throw IllegalStateException("JSON 解析失败：${first.message}，内容：${text.take(240)}", first)
        }
    }

    /**
     * 从文本中提取“第一段完整 JSON 对象”（支持前后多余内容）。
     * 做法：从第一个 '{' 开始按括号深度扫描，兼顾字符串内的转义。
     */
    private fun extractJsonObjectCandidate(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escape = false

        for (i in start until text.length) {
            val c = text[i]

            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 容错清理：去掉 JSON 里常见的“尾逗号”（例如 `{"a":1,}`）。
     */
    private fun cleanupLooseJson(text: String): String {
        // trailing comma before } or ]
        return text.replace(Regex(",\\s*([}\\]])"), "$1")
    }

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
