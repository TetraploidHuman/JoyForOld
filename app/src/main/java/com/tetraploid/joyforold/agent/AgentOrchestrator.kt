package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import kotlinx.coroutines.delay

class AgentOrchestrator(
    private val deepSeekClient: DeepSeekClient = DeepSeekClient(),
) {
    private data class PendingUserReply(
        val originalCommand: String,
        val aiPrompt: String,
    )

    private var pendingUserReply: PendingUserReply? = null

    fun clearPendingUserReply() {
        pendingUserReply = null
    }

    suspend fun run(
        userCommand: String,
        apiKey: String,
    ): AgentRunResult {
        val command = userCommand.trim()
        if (command.isEmpty()) {
            return AgentRunResult(false, "请输入指令", emptyList())
        }

        val service = JoyAccessibilityService.instance
            ?: return AgentRunResult(false, "请先开启无障碍服务", emptyList())

        // AI 曾通过 finish + waiting_for_user 提问：结合用户回答与当前页面续跑
        pendingUserReply?.let { pending ->
            pendingUserReply = null
            val pageContext = service.snapshotCompactForAgent()
            val planJson = try {
                deepSeekClient.planAfterUserReply(
                    originalCommand = pending.originalCommand,
                    assistantPrompt = pending.aiPrompt,
                    userAnswer = command,
                    pageContext = pageContext,
                    apiKey = apiKey,
                )
            } catch (error: Exception) {
                return AgentRunResult(false, error.message ?: "AI 续跑失败", emptyList())
            }
            val steps = ActionPlanNormalizer.normalize(
                pending.originalCommand,
                AgentAction.parsePlan(planJson),
            )
            return executeSteps(
                service,
                steps,
                source = "AI续跑",
                userCommand = pending.originalCommand,
                apiKey = apiKey,
            )
        }

        val pageContext = service.snapshotCompactForAgent()

        LocalCommandParser.parse(command)?.let { localSteps ->
            if (LocalCommandParser.isSendToSpecificPerson(command) && looksLikeOpenChatPage(pageContext)) {
                val aiJson = try {
                    deepSeekClient.planBatch(command, pageContext, apiKey)
                } catch (error: Exception) {
                    return AgentRunResult(false, error.message ?: "AI 请求失败", emptyList())
                }
                val aiSteps = ActionPlanNormalizer.normalize(command, AgentAction.parsePlan(aiJson))
                return executeSteps(service, aiSteps, source = "AI聊天页", userCommand = command, apiKey = apiKey)
            }

            val localResult = executeSteps(
                service,
                ActionPlanNormalizer.normalize(command, localSteps),
                source = "本地",
                userCommand = command,
                apiKey = apiKey,
            )
            if (localResult.success) {
                return localResult
            }

            val fallbackJson = try {
                deepSeekClient.planBatch(command, pageContext, apiKey)
            } catch (error: Exception) {
                return localResult
            }
            val fallbackSteps = ActionPlanNormalizer.normalize(command, AgentAction.parsePlan(fallbackJson))
            val aiResult = executeSteps(
                service,
                fallbackSteps,
                source = "AI通用回退",
                userCommand = command,
                apiKey = apiKey,
            )
            if (aiResult.success) {
                val mergedLogs = localResult.logs + aiResult.logs.map { log ->
                    log.copy(step = log.step + localResult.logs.size)
                }
                return aiResult.copy(logs = mergedLogs)
            }
            return aiResult
        }

        val planJson = try {
            deepSeekClient.planBatch(command, pageContext, apiKey)
        } catch (error: Exception) {
            return AgentRunResult(false, error.message ?: "AI 请求失败", emptyList())
        }

        val plannedSteps = ActionPlanNormalizer.normalize(command, AgentAction.parsePlan(planJson))
        val result = executeSteps(service, plannedSteps, source = "AI批量", userCommand = command, apiKey = apiKey)

        if (result.success || result.logs.none { !it.success }) {
            return result
        }

        val lastFailed = result.logs.lastOrNull { !it.success } ?: return result
        return retryOnce(service, command, apiKey, lastFailed.detail, result.logs)
    }

    private suspend fun retryOnce(
        service: JoyAccessibilityService,
        command: String,
        apiKey: String,
        lastResult: String,
        previousLogs: List<AgentStepLog>,
    ): AgentRunResult {
        val pageContext = service.snapshotCompactForAgent()
        val json = try {
            deepSeekClient.replanStep(command, pageContext, apiKey, lastResult)
        } catch (error: Exception) {
            return AgentRunResult(false, error.message ?: "AI 重试失败", previousLogs)
        }

        val action = AgentAction.fromJson(json)
        val stepIndex = previousLogs.size + 1
        if (action.action.equals("finish", ignoreCase = true) || action.finished) {
            return finishStepResult(action, userCommand = command, source = "AI重试", stepNo = stepIndex, logs = previousLogs)
        }

        val detail = executeAction(service, action)
        val logs = previousLogs + AgentStepLog(stepIndex, action, !detail.contains("失败"), detail)
        val success = !detail.contains("失败")
        return AgentRunResult(success, if (success) detail else "部分步骤失败：$detail", logs)
    }

    private suspend fun executeSteps(
        service: JoyAccessibilityService,
        steps: List<AgentAction>,
        source: String,
        userCommand: String = "",
        apiKey: String = "",
    ): AgentRunResult {
        val logs = mutableListOf<AgentStepLog>()
        var stepNo = 0
        var typedSuccessfully = false
        var sentSuccessfully = false

        for (action in steps) {
            if (action.action.equals("finish", ignoreCase = true) || action.finished) {
                if (SendIntentDetector.isSendCommand(userCommand) &&
                    !LocalCommandParser.isSendToSpecificPerson(userCommand) &&
                    !isCallIntent(userCommand) &&
                    !isSmsIntent(userCommand) &&
                    typedSuccessfully && !sentSuccessfully
                ) {
                    val autoSend = executeAction(service, AgentAction(action = "send"))
                    stepNo++
                    val sendOk = !autoSend.contains("失败")
                    logs += AgentStepLog(stepNo, AgentAction(action = "send"), sendOk, "[$source-补发] $autoSend")
                    if (sendOk) sentSuccessfully = true
                }
                return finishStepResult(action, userCommand, source, stepNo + 1, logs)
            }

            stepNo++
            val detail = executeAction(service, action)
            val success = !detail.contains("失败")
            logs += AgentStepLog(stepNo, action, success, "[$source] $detail")

            if (action.action.equals("type", ignoreCase = true) && success) {
                typedSuccessfully = true
                if (SendIntentDetector.isSendCommand(userCommand) &&
                    !LocalCommandParser.isSendToSpecificPerson(userCommand) &&
                    !isCallIntent(userCommand) &&
                    !isSmsIntent(userCommand) &&
                    !sentSuccessfully
                ) {
                    delay(ACTION_DELAY_MS)
                    stepNo++
                    val autoSend = executeAction(service, AgentAction(action = "send"))
                    val sendOk = !autoSend.contains("失败")
                    logs += AgentStepLog(stepNo, AgentAction(action = "send"), sendOk, "[$source-紧随输入] $autoSend")
                    if (sendOk) sentSuccessfully = true
                }
            }
            if ((action.action.equals("send", ignoreCase = true) || action.isSendClick()) && success) {
                sentSuccessfully = true
            }

            if (!success) {
                if (apiKey.isNotBlank() &&
                    !action.action.equals("wait", ignoreCase = true) &&
                    (detail.contains("未找到") || detail.contains("失败"))
                ) {
                    val pageContext = service.snapshotCompactForAgent()
                    val json = try {
                        deepSeekClient.replanStep(userCommand, pageContext, apiKey, detail)
                    } catch (_: Exception) {
                        return AgentRunResult(false, detail, logs)
                    }
                    val replanned = AgentAction.fromJson(json)
                    if (replanned.action.equals("finish", ignoreCase = true) || replanned.finished) {
                        return finishStepResult(replanned, userCommand, "$source-重规划", stepNo + 1, logs)
                    }
                    val replannedDetail = executeAction(service, replanned)
                    val replannedOk = !replannedDetail.contains("失败")
                    stepNo++
                    logs += AgentStepLog(stepNo, replanned, replannedOk, "[$source-重规划] $replannedDetail")
                    if (!replannedOk) {
                        return AgentRunResult(false, replannedDetail, logs)
                    }
                    continue
                }
                return AgentRunResult(false, detail, logs)
            }

            if (needsNavigationDelay(action)) {
                delay(NAVIGATION_DELAY_MS)
            } else {
                delay(ACTION_DELAY_MS)
            }
        }

        if (SendIntentDetector.isSendCommand(userCommand) &&
            !LocalCommandParser.isSendToSpecificPerson(userCommand) &&
            !isCallIntent(userCommand) &&
            !isSmsIntent(userCommand) &&
            typedSuccessfully && !sentSuccessfully
        ) {
            stepNo++
            val autoSend = executeAction(service, AgentAction(action = "send"))
            val sendOk = !autoSend.contains("失败")
            logs += AgentStepLog(stepNo, AgentAction(action = "send"), sendOk, "[$source-补发] $autoSend")
        }

        return AgentRunResult(true, "步骤已执行", logs)
    }

    private fun finishStepResult(
        action: AgentAction,
        userCommand: String,
        source: String,
        stepNo: Int,
        logs: List<AgentStepLog>,
    ): AgentRunResult {
        val rawSummary = action.message ?: "任务已完成"
        val (summary, shouldWait) = resolveWaitForUser(action, userCommand, rawSummary)
        val updatedLogs = logs + AgentStepLog(stepNo, action, true, "[$source] $summary")

        if (shouldWait) {
            pendingUserReply = PendingUserReply(
                originalCommand = extractRootCommand(userCommand),
                aiPrompt = summary,
            )
            return AgentRunResult(
                success = true,
                summary = summary,
                logs = updatedLogs,
                waitingForUserConfirm = true,
                confirmPrompt = summary,
            )
        }

        return AgentRunResult(true, summary, updatedLogs)
    }

    /**
     * AI 用 finish 提问时应带 waiting_for_user:true。
     * 若 AI 问了但没设 flag，或打电话场景下回了“请描述页面”这类无效 finish，仍进入语音等待。
     */
    private fun resolveWaitForUser(
        action: AgentAction,
        userCommand: String,
        rawSummary: String,
    ): Pair<String, Boolean> {
        if (action.waitingForUser) {
            return normalizeAiWaitPrompt(userCommand, rawSummary) to true
        }
        if (looksLikeAiQuestion(rawSummary)) {
            return rawSummary to true
        }

        val root = extractRootCommand(userCommand)
        if (isCallIntent(root) && !hasExplicitCallRoute(root) && looksLikeWrongAiPrompt(rawSummary)) {
            return normalizeAiWaitPrompt(userCommand, rawSummary) to true
        }

        return rawSummary to false
    }

    private fun looksLikeAiQuestion(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return text.contains("？") ||
            text.contains("?") ||
            text.contains("请说") ||
            text.contains("请选择") ||
            text.contains("请确认") ||
            text.contains("你要") && (text.contains("还是") || text.contains("哪种") || text.contains("哪里"))
    }

    private fun looksLikeWrongAiPrompt(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("打开") ||
            lower.contains("页面") ||
            lower.contains("告诉我") ||
            lower.contains("描述") ||
            lower.contains("当前页面")
    }

    /** AI 已决定要问用户，但偶尔问错（如让用户描述页面）；仅修正文案，不替 AI 决定是否询问。 */
    private fun normalizeAiWaitPrompt(userCommand: String, message: String): String {
        val root = extractRootCommand(userCommand)
        if (!isCallIntent(root) || hasExplicitCallRoute(root)) return message

        val lower = message.lowercase()
        val alreadyAsksRoute = (lower.contains("qq") && lower.contains("手机")) ||
            lower.contains("在哪里打") ||
            lower.contains("哪种电话")
        if (alreadyAsksRoute) return message

        val looksLikeWrongPrompt = looksLikeWrongAiPrompt(message)
        if (looksLikeWrongPrompt) {
            return "你要在哪里打电话？请说 QQ电话 或 手机电话。"
        }
        return message
    }

    private fun extractRootCommand(command: String): String {
        return command.lineSequence()
            .firstOrNull { it.trimStart().startsWith("原指令：") }
            ?.removePrefix("原指令：")
            ?.trim()
            ?: command.trim()
    }

    private fun hasExplicitCallRoute(command: String): Boolean {
        val lower = command.lowercase()
        return lower.contains("qq") ||
            lower.contains("腾讯") ||
            lower.contains("手机电话") ||
            lower.contains("系统电话") ||
            lower.contains("系统拨号") ||
            (lower.contains("手机") && lower.contains("打"))
    }

    private fun isCallIntent(command: String): Boolean {
        val lower = command.trim().lowercase()
        val hasCallCore = lower.contains("电话") ||
            lower.contains("通话") ||
            lower.contains("呼叫") ||
            lower.contains("拨号") ||
            lower.contains("拨打") ||
            lower.contains("视频通话") ||
            lower.contains("语音通话") ||
            lower.contains("语音电话")

        val hasAction = lower.contains("打") ||
            lower.contains("拨") ||
            lower.contains("呼叫") ||
            lower.contains("接通") ||
            lower.contains("通话")

        return (hasCallCore && hasAction) || lower.contains("call")
    }

    private fun isSmsIntent(command: String): Boolean {
        val lower = command.trim().lowercase()
        return lower.contains("发短信") ||
            lower.contains("发送短信") ||
            lower.contains("短信") ||
            lower.contains("留言") ||
            lower.contains("sms")
    }

    private fun looksLikeOpenChatPage(pageContext: String): Boolean {
        val text = pageContext.lowercase()
        val hasInput = text.contains("可输入(") || text.contains("输入区") || text.contains("input")
        val hasSend = text.contains("发送相关(") || text.contains("send-like") || text.contains("发送")
        val hasChatHint = text.contains("聊天") || text.contains("会话") || text.contains("当前为 qq") || text.contains("当前为微信")
        return hasInput && hasSend && hasChatHint
    }

    private fun AgentAction.isSendClick(): Boolean {
        return action.equals("click", ignoreCase = true) &&
            targetText?.contains("发送", ignoreCase = true) == true
    }

    private suspend fun executeAction(service: JoyAccessibilityService, action: AgentAction): String {
        if (action.action.equals("wait", ignoreCase = true)) {
            delay(NAVIGATION_DELAY_MS)
            return "等待界面刷新"
        }
        return service.execute(action)
    }

    private fun needsNavigationDelay(action: AgentAction): Boolean {
        return action.action.equals("click", ignoreCase = true) ||
            action.action.equals("back", ignoreCase = true)
    }

    companion object {
        private const val ACTION_DELAY_MS = 80L
        private const val NAVIGATION_DELAY_MS = 220L
    }
}
