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
        toolsPrompt: String? = null,
        loopContext: String = "",
        screenshotBase64: String? = null,
        visionMode: Boolean = false,
    ): JSONObject {
        conversation.seedSystem(buildSystemPrompt(keyMemories, toolsPrompt, visionMode))
        conversation.addUser(
            buildPlanningUserMessage(
                header = "【用户指令】$userCommand",
                loopContext = loopContext,
                pageContext = pageContext,
                pageDiff = pageDiff,
                minimalPageContext = minimalPageContext,
                pageContextMode = pageContextMode,
                visionMode = visionMode,
            ),
        )

        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 LLM API Key")
            val body = buildPlanningRequestBody(conversation, screenshotBase64)
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
        loopContext: String = "",
        screenshotBase64: String? = null,
        visionMode: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalArgumentException("请先填写 LLM API Key")

        ensureSystemSeeded(conversation, keyMemories, visionMode)

        conversation.addUser(
            buildPlanningUserMessage(
                header = stepFeedback,
                loopContext = loopContext,
                pageContext = pageContext,
                pageDiff = pageDiff,
                minimalPageContext = minimalPageContext,
                pageContextMode = pageContextMode,
                visionMode = visionMode,
            ),
        )

        val body = buildPlanningRequestBody(conversation, screenshotBase64)
        val content = postChatRaw(apiKey, body)
        conversation.addAssistant(content)
        parseJsonObject(content)
    }

    suspend fun extractKeyMemory(
        apiKey: String,
        sessionSummary: String,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || sessionSummary.isBlank()) return@withContext ""

        val body = LlmApiSupport.buildSimpleRequestBody(
            baseUrl = BuildConfig.LLM_BASE_URL,
            model = BuildConfig.LLM_MODEL,
            systemPrompt = "从手机助手任务记录中提取一条可复用的用户偏好/联系人信息（1句话，30字内），" +
                "例如常用联系人、默认打电话方式、常用应用。不要复述本次具体任务步骤。" +
                "若无可复用信息，只返回空字符串。只返回纯文本，不要 JSON。",
            userPrompt = sessionSummary,
            maxTokens = 120,
            jsonObjectOutput = false,
        )

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
        visionMode: Boolean = false,
        toolsPrompt: String? = null,
    ) {
        val prompt = buildSystemPrompt(keyMemories, toolsPrompt, visionMode)
        if (!conversation.hasSystem()) {
            conversation.seedSystem(prompt)
            return
        }
        if (visionMode) {
            conversation.refreshSystem(prompt)
        }
    }

    suspend fun classifyPresetIntent(
        apiKey: String,
        utterance: String,
    ): Pair<String, Double>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return@withContext null

        val body = LlmApiSupport.buildSimpleRequestBody(
            baseUrl = BuildConfig.LLM_BASE_URL,
            model = BuildConfig.LLM_MODEL,
            systemPrompt = """
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
            userPrompt = "用户原话：$trimmed",
            maxTokens = 60,
            jsonObjectOutput = true,
        )

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

    suspend fun classifySystemIntent(
        apiKey: String,
        utterance: String,
    ): SystemIntentAiResolver.Classification? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return@withContext null

        val nowHint = com.tetraploid.joyforold.system.TimeFormatter.spokenNow()
        val body = LlmApiSupport.buildSimpleRequestBody(
            baseUrl = BuildConfig.LLM_BASE_URL,
            model = BuildConfig.LLM_MODEL,
            systemPrompt = """
                你是老年手机助手的「系统能力理解器」，负责从口语中识别闹钟/日程意图并提取参数。
                当前时间参考：$nowHint

                意图定义：
                - set_alarm：设闹钟、到点叫醒、定时响铃（如「明早七点叫我」「7点半闹钟」）
                - add_calendar_event：日历/日程/约会/记事提醒（如「明天下午三点提醒开会」「记一下周五体检」）
                - none：不属于以上，或只是闲聊/查询时间天气/打开应用

                参数规则：
                - time_hhmm：闹钟用，24 小时制 HH:mm（如 07:00、19:30）；相对时间请结合当前时间换算
                - title：简短标题（吃药、开会、体检…）
                - notes：补充说明，可为空
                - event_time_iso：日程开始时间，ISO-8601（如 2026-07-11T15:00:00+08:00）；相对日期请换算
                - clarify：缺关键信息时用一句话追问（如缺闹钟时间）；信息足够则留空
                - confidence：0~1；不确定时 intent=none 或降低 confidence

                严格返回 JSON，不要多余文字：
                {"intent":"set_alarm|add_calendar_event|none","confidence":0.0,"time_hhmm":"","title":"","notes":"","event_time_iso":"","clarify":""}
            """.trimIndent(),
            userPrompt = "用户原话：$trimmed",
            maxTokens = 180,
            jsonObjectOutput = true,
        )

        val content = try {
            postChatRaw(apiKey, body)
        } catch (_: Exception) {
            return@withContext null
        }

        val json = runCatching { JSONObject(content) }.getOrNull() ?: return@withContext null
        SystemIntentAiResolver.Classification(
            intent = json.optString("intent").ifBlank { "none" },
            confidence = json.optDouble("confidence", 0.0),
            timeHhmm = json.optString("time_hhmm").ifBlank { null },
            title = json.optString("title").ifBlank { null },
            notes = json.optString("notes").ifBlank { null },
            eventTimeIso = json.optString("event_time_iso").ifBlank { null },
            clarify = json.optString("clarify").ifBlank { null },
        )
    }

    private fun buildPlanningUserMessage(
        header: String,
        loopContext: String,
        pageContext: String,
        pageDiff: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode,
        visionMode: Boolean = false,
    ): String = buildString {
        appendLine(header)
        if (loopContext.isNotBlank()) {
            appendLine()
            appendLine(loopContext)
        }
        append(
            AgentMessageCompactor.formatPageSection(
                pageContext = pageContext,
                pageDiff = pageDiff,
                minimalPageContext = minimalPageContext,
                mode = pageContextMode,
            ),
        )
        if (visionMode) {
            appendLine()
            appendLine(
                "【视觉观察】无障碍树为空或不可用，本条消息附带当前屏幕截图。" +
                    "请根据截图识别按钮/列表/输入框位置；点击用 tap，target_text 填归一化坐标 x,y（0~1000，左上为原点）。" +
                    "例：点击聊天列表第二行 → {\"action\":\"tap\",\"target_text\":\"500,280\"}。",
            )
        }
        appendLine()
        appendLine(
            "请只规划 **1 步** action（actions 数组最多 1 项，或单步 {\"action\":...}）。" +
                "open_app/list_apps/finish/read_tree/send 各占一步。执行后你会收到【上一步执行结果】与最新页面观察，再规划下一步。",
        )
    }

    private fun buildSystemPrompt(
        keyMemories: String,
        toolsPrompt: String? = null,
        visionMode: Boolean = false,
    ): String = """
        你是手机操作 Agent，工作方式：观察页面 → 选一步工具 → 看结果 → 再观察（Open-AutoGLM 单步循环）。
        ${toolsPrompt ?: AgentToolRegistry.descriptionsForPrompt(visionMode)}

        【历史关键记忆（仅供参考）】
        $keyMemories

        【状态与指令分离（Sanna）】
        - system 消息只有规则与工具说明；**当前页面树/快览/变化只出现在 user 消息**，以最新一条为准。
        - 历史 user 消息中的旧页面快照可能已省略，禁止依据旧快照决策。

        【原则】
        - **必须以本轮【用户指令】为唯一目标**；历史记忆只能辅助，禁止擅自继续上一轮未提及的任务。
        - 每次只规划 **1 步** action；执行后必须根据【页面变化】与【执行验证】再规划下一步。
        - **action 必须在工具白名单内**，只允许：${AgentToolRegistry.toolNames.joinToString()}；open_app、list_apps、finish、read_tree、send 各占一步。
        - 能走系统级动作时优先走系统动作（dial_contact/send_sms/set_alarm/add_calendar_event/open_*），避免纯 UI 点按。
        - **观察驱动**：每一步后阅读【页面变化】；若显示页面无明显变化/指纹未变，说明上一步未推进，必须 read_tree 换目标，**禁止**重复相同 click/type。
        - **完成判定**：finish 前确认【页面快览】中出现你声称的内容（歌名/标题等）；若页面是推荐列表且没有目标词，说明点错了，继续操作勿 finish。
        - **视频/音乐播放（哔哩哔哩等）**：点击搜索结果进入详情页后，通常已自动播放；若快览含「条弹幕/万播放/正在看」且标题含目标词，直接 finish，**禁止**再 click「播放按钮」「视频播放区域」等无障碍树中不存在的控件。
        - **闹钟/日程**：必须用 set_alarm 或 add_calendar_event，禁止 open_app(时钟/日历)+click；时间放 target_text（HH:mm），标题/备注放 input_text。
        - **系统设置/打开应用**：优先 open_wifi_settings/open_bluetooth_settings/open_settings/open_app 等系统动作，不要进设置 App 点按。
        - 找联系人：优先可见列表模糊匹配（同音字、谐音、号码片段），直接 click；找不到先 scroll_down 或 swipe_down。
        - **find_on_page**：仅探测当前屏是否已有某文字，**不能代替导航**；同一关键词连续 2 次未找到时，必须 read_tree 查看可点击/可输入项，再根据【用户指令】与【页面快览】自主规划 click/type/scroll，禁止假设固定路径。
        - 需要切换应用时：不确定应用名先 list_apps（可带 target_text 筛选），再用 open_app；target_text **必须**与 list_apps 返回的名称逐字一致，禁止猜测。
        - **open_app**：若执行成功但【执行验证】提示「未能捕获快照」，应用可能已打开，必须先 wait/read_tree 确认，禁止直接 finish 声称未安装。
        - 不确定时用 find_on_page 搜索；结构复杂用 read_tree。
        - **上一步失败后禁止重复相同操作**；必须换策略（读树/滚动/换应用/询问用户），具体路径由你根据页面决定。
        - **敏感操作必须先询问用户**（finish + waiting_for_user:true）：
          · 拨打电话、点击拨打/通话按钮 → needs_binary_confirm:true
          · 发送消息（send 或点击发送）→ needs_binary_confirm:true
          · 给指定联系人发消息输入完成后 → needs_binary_confirm:true
          · 联系人歧义、QQ电话 vs 手机电话未明确 → needs_binary_confirm:false（开放问答）
        - **闲聊/问候/说明性回复**：finished:true, waiting_for_user:false；不要因为 message 带问号就设 waiting_for_user。
        - 任务完成：finish, finished:true；需用户回复：waiting_for_user:true（并按上文设置 needs_binary_confirm）。
        ${if (visionMode) visionModeRules() else ""}
    """.trimIndent()

    private fun visionModeRules(): String = """
        【视觉兜底模式】
        - 当前应用**不提供可用无障碍 UI 信息**；消息中不会包含「可点击/可输入/结构树」等空列表，**以附带截图为主要依据**规划每一步。
        - 点击用 tap（不用 click）；target_text 为归一化坐标 "x,y"（0~1000，左上为原点）。
        - 需要输入文字：观察截图定位输入框 → tap 输入框 → 下一步 type（input_text 填内容）；不要连续 tap 同一区域却不 type。
        - 发送：优先 send；若无障碍找不到发送按钮，send 的 target_text 可填发送按钮坐标 "x,y"。
        - 禁止 read_tree / find_on_page / click（无障碍树为空时无效）。
        - 自行从【用户指令】分解子目标；指令中的关键信息（人名、关键词等）若明显不完整或含糊，finish+waiting_for_user 向用户确认，勿猜测。
        - 【页面变化】含「视觉截图已变化」表示上一步可能已生效；含「截图未变」则换策略，勿重复相同 tap。
    """.trimIndent()

    private fun buildPlanningRequestBody(
        conversation: AgentConversationSession,
        screenshotBase64: String?,
    ): JSONObject = LlmApiSupport.buildPlanningRequestBody(
        baseUrl = BuildConfig.LLM_BASE_URL,
        model = BuildConfig.LLM_MODEL,
        systemInstructions = conversation.systemInstructions(),
        chatMessages = conversation.toApiMessages(screenshotBase64),
        responsesInput = conversation.toResponsesApiInput(screenshotBase64),
        maxTokens = AgentContextLimits.PLAN_MAX_TOKENS,
        jsonObjectOutput = true,
    )

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
        throw lastError ?: IllegalStateException("LLM 请求失败")
    }

    private suspend fun postChatRawOnce(apiKey: String, body: JSONObject): String {
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(BuildConfig.LLM_BASE_URL)
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
                                "LLM 请求失败 (${response.code}): ${responseBody.take(500)}",
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

    private fun extractAssistantContent(responseBody: String): String =
        LlmApiSupport.extractAssistantContent(responseBody, BuildConfig.LLM_BASE_URL)

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
