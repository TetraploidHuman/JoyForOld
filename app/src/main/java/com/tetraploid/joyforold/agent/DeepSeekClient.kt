package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.network.JoyHttpClients
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

class DeepSeekClient(
    private val httpClient: HttpClient = JoyHttpClients.llm(),
) : AgentLlmClient {
    override suspend fun beginTask(
        apiKey: String,
        conversation: AgentConversationSession,
        userCommand: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode,
        toolsPrompt: String?,
        loopContext: String,
        screenshotBase64: String?,
        visionMode: Boolean,
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
            val content = postChatRaw(apiKey, body, phase = "beginTask", conversation = conversation)
            conversation.addAssistant(content)
            parseJsonObject(content)
        }
    }

    override suspend fun continueAfterStep(
        apiKey: String,
        conversation: AgentConversationSession,
        stepFeedback: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode,
        loopContext: String,
        screenshotBase64: String?,
        visionMode: Boolean,
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
        val content = postChatRaw(apiKey, body, phase = "continueAfterStep", conversation = conversation)
        conversation.addAssistant(content)
        parseJsonObject(content)
    }

    override suspend fun extractKeyMemory(
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
            postChatRaw(apiKey, body, phase = "extractKeyMemory").trim().take(120)
        } catch (_: Exception) {
            sessionSummary.lineSequence().firstOrNull { it.startsWith("指令：") }
                ?.removePrefix("指令：")
                ?.trim()
                ?.take(40)
                .orEmpty()
        }
    }

    override suspend fun planUserFacingPhases(
        apiKey: String,
        userCommand: String,
    ): List<TaskPhaseItem> = withContext(Dispatchers.IO) {
        val command = userCommand.trim()
        if (apiKey.isBlank() || command.isBlank()) return@withContext emptyList()

        val body = LlmApiSupport.buildSimpleRequestBody(
            baseUrl = BuildConfig.LLM_BASE_URL,
            model = BuildConfig.LLM_MODEL,
            systemPrompt = """
                你是老年手机助手的「任务阶段规划器」。
                根据用户指令，拆成给用户看的粗略阶段（类似 Codex 的 plan：只写目标级步骤，不写具体点击）。

                要求：
                - 数组必须按时间顺序：先做什么、再做什么（打开 App → 搜索 → 进入播放…）
                - 2~5 个阶段；语气短、口语、中文
                - 写「做什么」（打开哔哩哔哩、搜索片名、进入并播放），不要写控件名/坐标/tap
                - 不要拆成细碎 UI 操作（禁止「点击搜索框」之类过细步骤）
                - **禁止**写「结束任务 / 完成任务 / 播放后结束」等收尾阶段；完成由系统自动判定
                - 简单一事（如「现在几点」）可以只有 1 个阶段

                严格返回 JSON，不要多余文字：
                {"phases":["打开哔哩哔哩","搜索假面骑士","进入并播放"]}
            """.trimIndent(),
            userPrompt = "【用户指令】$command",
            maxTokens = 160,
            jsonObjectOutput = true,
        )

        val content = try {
            postChatRaw(apiKey, body, phase = "planUserFacingPhases")
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        val json = runCatching { parseJsonObject(content) }.getOrNull()
            ?: return@withContext emptyList()
        TaskPhasePlanner.parseFromLlmJson(json)
    }

    override fun ensureSystemSeeded(
        conversation: AgentConversationSession,
        keyMemories: String,
        visionMode: Boolean,
        toolsPrompt: String?,
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

    override suspend fun classifyPresetIntent(
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
            postChatRaw(apiKey, body, phase = "classifyPresetIntent")
        } catch (_: Exception) {
            return@withContext null
        }

        val json = runCatching { JSONObject(content) }.getOrNull() ?: return@withContext null
        val intent = json.optString("intent").ifBlank { "none" }
        val confidence = json.optDouble("confidence", 0.0)
        intent to confidence
    }

    override suspend fun resolveActionSetAsk(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        writeFields: List<String>,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || writeFields.isEmpty()) return@withContext emptyMap()
        val fieldsHint = writeFields.joinToString(", ")
        val body = LlmApiSupport.buildSimpleRequestBody(
            baseUrl = BuildConfig.LLM_BASE_URL,
            model = BuildConfig.LLM_MODEL,
            systemPrompt = systemPrompt.trim(),
            userPrompt = buildString {
                appendLine(userPrompt.trim())
                appendLine()
                append("请只返回包含这些字段的 JSON 对象：$fieldsHint")
            },
            // 商品/会话标题常较长，120 易截断导致字段解析失败
            maxTokens = 512,
            jsonObjectOutput = true,
        )
        val content = try {
            postChatRaw(apiKey, body, phase = "resolveActionSetAsk")
        } catch (_: Exception) {
            return@withContext emptyMap()
        }
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return@withContext emptyMap()
        writeFields.mapNotNull { field ->
            val value = json.optString(field).trim()
            if (value.isNotEmpty()) field to value else null
        }.toMap()
    }

    override suspend fun classifySystemIntent(
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
                你是老年手机助手的「系统能力理解器」，负责从口语中识别闹钟/日程/导航意图并提取参数。
                当前时间参考：$nowHint

                意图定义：
                - set_alarm：设闹钟、到点叫醒、定时响铃（如「明早七点叫我」「7点半闹钟」）
                - add_calendar_event：日历/日程/约会/记事提醒（如「明天下午三点提醒开会」「记一下周五体检」）
                - navigate_home：导航回家/回家里（如「带我回家」「导航回家」）
                - navigate_to：用户要**直接导航**、不必先选地点列表。典型：想去最近/附近/就近的一家、说随便哪家、给了具体门牌/路址、或明确只要一家即可
                - navigate_pick：目的地可能有多家同名点，应先列候选让用户选。典型：只说校名/店名/公园名且未表达「就近/随便」
                - none：不属于以上，或只是闲聊/查询时间天气/打开应用/在 IM 里发消息

                导航判定要点（按语义理解，不要死磕个别用词）：
                - **不是导航**：去微信/TIM/QQ/钉钉给某人发消息、打开某 App 再操作，即使句首有「去」→ intent=none
                - 用户表达了就近、最近、附近、顺路、随便一家、最近的那家等（相对**当前位置**） → navigate_to
                - 用户点名一个可能有多处的地点且未限定就近 → navigate_pick
                - 「A附近/旁边的B」（如桂阳一中附近的肯德基、郴州市一中附近的KFC）：destination=B，near_landmark=A；**优先 navigate_to**（就近一家直达），除非用户明确要挑某一家
                - 「行政区的B」（如郴州市北湖区的肯德基）：destination=B，near_landmark=行政区；多家可用 navigate_pick，明确最近一家用 navigate_to
                - destination 填清洗后的目的地关键词（去掉「带我去/最近的」等前缀；英文品牌可转中文如 kfc→肯德基）
                - near_landmark：相对某地标或行政区时填写（如「郴州市一中」）；相对当前位置的「附近的肯德基」不要填 near_landmark

                参数规则：
                - time_hhmm：闹钟用，24 小时制 HH:mm（如 07:00、19:30）；相对时间请结合当前时间换算
                - title：简短标题（吃药、开会、体检…）
                - notes：补充说明，可为空
                - event_time_iso：日程开始时间，ISO-8601（如 2026-07-11T15:00:00+08:00）；相对日期请换算
                - destination：导航目的地关键词（品类/店名/地址）；回家意图可留空
                - near_landmark：参考地标（学校/车站等）；无则留空
                - clarify：缺关键信息时用一句话追问（如缺闹钟时间）；信息足够则留空
                - confidence：0~1；不确定时 intent=none 或降低 confidence

                严格返回 JSON，不要多余文字：
                {"intent":"set_alarm|add_calendar_event|navigate_home|navigate_to|navigate_pick|none","confidence":0.0,"time_hhmm":"","title":"","notes":"","event_time_iso":"","destination":"","near_landmark":"","clarify":""}
            """.trimIndent(),
            userPrompt = "用户原话：$trimmed",
            maxTokens = 240,
            jsonObjectOutput = true,
        )

        val content = try {
            postChatRaw(apiKey, body, phase = "classifySystemIntent")
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
            destination = json.optString("destination").ifBlank { null },
            nearLandmark = json.optString("near_landmark").ifBlank { null },
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
                "open_app/list_apps/finish/read_tree/query_page/query_tree/send 各占一步。" +
                "执行后你会收到【上一步执行结果】与最新页面观察，再规划下一步。",
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
        - 历史 user 消息中更早的页面快照可能已省略；以最新【当前页面】/【当前页面快览】为准。若最新仅为摘要但会话中仍保留一条完整快览，可结合该完整快览中的控件文案选目标。

        【原则】
        - **必须以本轮【用户指令】为唯一目标**；历史记忆只能辅助，禁止擅自继续上一轮未提及的任务。
        - 每次只规划 **1 步** action；执行后必须根据【页面变化】与【执行验证】再规划下一步。
        - **action 必须在工具白名单内**，只允许：${AgentToolRegistry.toolNames.joinToString()}；open_app、list_apps、finish、read_tree、query_page、query_tree、send 各占一步。
        - **run_action_set**：仅当你主动选择固定动作组（ActionSet）时调用（见工具说明）；不要因用户句式像某动作组就默认调用；参数不全时用 finish+waiting_for_user 追问或逐步 UI 操作。
        - **发消息**：用哪个 App（微信/TIM/QQ/短信等）由话术决定，未点名不要默认微信；优先 open_app + click/type/send 逐步操作。send_im_message 动作组仅微信可用，非必须，不要因为句式像发消息就默认调用。
        - 能走系统级动作时优先走系统动作（dial_contact/send_sms/set_alarm/add_calendar_event/navigate_to/navigate_home/open_*），避免纯 UI 点按。
        - **观察驱动**：每一步后阅读【页面变化】；若显示页面无明显变化/指纹未变，说明上一步未推进，必须换目标，**禁止**重复相同 click/type。控件细节不足时优先 query_page / query_tree 查本地观察仓，再决定 click。
        - **完成判定**：finish 前确认【页面快览】中出现你声称的内容（歌名/标题等）；若页面是推荐列表且没有目标词，说明点错了，继续操作勿 finish。
        - **地图导航**：回家用 navigate_home。用户要就近/最近/随便一家或已给具体地址 → **navigate_to** 后 finish；只点名可能有多处分店的地点且未限定就近 → **navigate_pick**。「A附近的B」时 target_text=B、input_text=A。**禁止**默认用 click 点周边列表；仅深链失败或用户明确要 UI 挑店时才用 run_action_set / map_navigate。
        - **视频/音乐播放（哔哩哔哩等）**：点击搜索结果进入详情页后，通常已自动播放；若快览含「条弹幕/万播放/正在看」且标题含目标词，直接 finish，**禁止**再 click「播放按钮」「视频播放区域」等无障碍树中不存在的控件。
        - **闹钟/日程**：必须用 set_alarm 或 add_calendar_event，禁止 open_app(时钟/日历)+click；时间放 target_text（HH:mm），标题/备注放 input_text。
        - **系统设置/打开应用**：优先 open_wifi_settings/open_bluetooth_settings/open_settings/open_app 等系统动作，不要进设置 App 点按。
        - 找联系人：优先可见列表模糊匹配（同音字、谐音、号码片段），直接 click；找不到先 scroll_down 或 swipe_down。
        - **find_on_page**：仅探测当前屏是否已有某文字，**不能代替导航**；同一关键词连续 2 次未找到时，可 query_page/query_tree 或 read_tree 查看可点击/可输入项，再根据【用户指令】与【页面快览】自主规划 click/type/scroll，禁止假设固定路径。
        - 需要切换应用时：不确定应用名先 list_apps（可带 target_text 筛选），再用 open_app；target_text **必须**与 list_apps 返回的名称逐字一致，禁止猜测。
        - **open_app**：若执行成功但【执行验证】提示「未能捕获快照」，应用可能已打开，必须先 wait/read_tree/query_page 确认，禁止直接 finish 声称未安装。
        - 不确定时用 find_on_page 或 query_page；结构复杂用 query_tree / read_tree（结果会进本地观察仓，可复查）。
        - **上一步失败后禁止重复相同操作**；必须换策略（查观察仓/读树/滚动/换应用/询问用户），具体路径由你根据页面决定。
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

    private suspend fun postChatRaw(
        apiKey: String,
        body: JSONObject,
        phase: String,
        conversation: AgentConversationSession? = null,
    ): String {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return postChatRawOnce(apiKey, body, phase, conversation)
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

    private suspend fun postChatRawOnce(
        apiKey: String,
        body: JSONObject,
        phase: String,
        conversation: AgentConversationSession?,
    ): String {
        val response = httpClient.post(BuildConfig.LLM_BASE_URL) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "LLM 请求失败 (${response.status.value}): ${responseBody.take(500)}",
            )
        }
        val usage = LlmApiSupport.extractUsage(responseBody, BuildConfig.LLM_BASE_URL)
        LlmUsageLog.record(phase = phase, usage = usage, conversation = conversation)
        return extractAssistantContent(responseBody)
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
    }
}
