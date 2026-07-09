package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class AgentOrchestrator(
    private val deepSeekClient: DeepSeekClient = DeepSeekClient(),
    private var memoryStore: AgentMemoryStore? = null,
) {
    private data class PendingUserReply(
        val originalCommand: String,
        val aiPrompt: String,
        val sessionId: String,
    )

    private var pendingUserReply: PendingUserReply? = null

    fun bindMemoryStore(store: AgentMemoryStore) {
        memoryStore = store
    }

    fun clearPendingUserReply() {
        pendingUserReply = null
    }

    suspend fun run(
        userCommand: String,
        apiKey: String,
        runContext: AgentRunContext = AgentRunContext(),
        onProgress: ((Int, String) -> Unit)? = null,
    ): AgentRunResult {
        val command = userCommand.trim()
        if (command.isEmpty()) {
            return AgentRunResult(false, "请输入指令", emptyList())
        }

        val service = JoyAccessibilityService.instance
            ?: return AgentRunResult(false, "请先开启无障碍服务", emptyList())

        pendingUserReply?.let { pending ->
            pendingUserReply = null
            val enriched = buildString {
                appendLine("原指令：${pending.originalCommand}")
                appendLine("助手询问：${pending.aiPrompt}")
                appendLine("用户回答：$command")
            }.trim()
            return runAgentLoop(
                loopCommand = enriched,
                rootCommand = pending.originalCommand,
                apiKey = apiKey,
                service = service,
                runContext = runContext,
                onProgress = onProgress,
                resumeSessionId = pending.sessionId,
            )
        }

        LocalCommandParser.parse(command)?.let { localSteps ->
            if (LocalCommandParser.isSendToSpecificPerson(command)) {
                val pageContext = service.snapshotCompactForAgent()
                if (looksLikeOpenChatPage(pageContext)) {
                    return runAgentLoop(command, command, apiKey, service, runContext, onProgress)
                }
            }

            val localResult = executeLocalSteps(service, localSteps, command, runContext)
            if (localResult.success || localResult.waitingForUserConfirm) {
                return localResult
            }
        }

        return runAgentLoop(command, command, apiKey, service, runContext, onProgress)
    }

    private suspend fun runAgentLoop(
        loopCommand: String,
        rootCommand: String,
        apiKey: String,
        service: JoyAccessibilityService,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
        resumeSessionId: String? = null,
    ): AgentRunResult {
        val logs = mutableListOf<AgentStepLog>()
        val session = AgentConversationSession(
            sessionId = resumeSessionId ?: java.util.UUID.randomUUID().toString(),
            rootCommand = extractRootCommand(rootCommand),
        )
        val memories = memoryStore?.loadRecentMemories().orEmpty()
        val memoryPrompt = memoryStore?.formatMemoriesForPrompt(memories).orEmpty()

        var previousSnapshot: StructuredPageSnapshot? = null
        var stepNo = 0

        suspend fun captureObservation(): Pair<String, String> {
            val snapshots = service.captureStructuredSnapshots()
            val merged = service.mergeSnapshots(snapshots)
            if (merged == null) {
                return "无法读取页面，请切换到目标应用。" to "无法读取页面"
            }
            val pageContext = merged.toCompactSummary()
            val pageDiff = PageObservation.diff(previousSnapshot, merged)
            previousSnapshot = merged
            return pageContext to pageDiff
        }

        try {
            val (pageContext, pageDiff) = captureObservation()
            val effectiveCommand = if (loopCommand != rootCommand) loopCommand else session.rootCommand
            runContext.awaitContinuation()

            var json = deepSeekClient.beginTask(
                apiKey = apiKey,
                conversation = session,
                userCommand = effectiveCommand,
                pageContext = pageContext,
                pageDiff = pageDiff,
                keyMemories = memoryPrompt,
            )

            repeat(MAX_AGENT_STEPS) {
                coroutineContext.ensureActive()
                runContext.awaitContinuation()

                stepNo++
                runContext.updateProgress(stepNo, "规划第 $stepNo 步")
                onProgress?.invoke(stepNo, runContext.statusMessage)

                val action = AgentAction.fromJson(json)

                if (action.action.equals("finish", ignoreCase = true) || action.finished) {
                    return finishAndPersist(
                        action = action,
                        loopCommand = loopCommand,
                        session = session,
                        apiKey = apiKey,
                        source = if (resumeSessionId != null) "Agent续跑" else "Agent",
                        stepNo = stepNo,
                        logs = logs,
                        runContext = runContext,
                    )
                }

                runContext.updateProgress(stepNo, "执行：${action.action}")
                onProgress?.invoke(stepNo, runContext.statusMessage)

                val result = AgentToolRegistry.execute(service, action)
                val (_, pageDiff) = captureObservation()
                logs += AgentStepLog(
                    step = stepNo,
                    action = action,
                    success = result.success,
                    detail = "[Agent] ${result.toAgentFeedback()}",
                )
                session.recordStep(stepNo, action, result, pageDiff)

                val feedback = buildString {
                    appendLine("【上一步执行结果】")
                    appendLine("操作：${action.action}" +
                        action.targetText?.let { " target=\"$it\"" }.orEmpty() +
                        action.inputText?.let { " input=\"$it\"" }.orEmpty())
                    append(result.toAgentFeedback())
                }

                if (needsNavigationDelay(action)) {
                    delay(NAVIGATION_DELAY_MS)
                } else if (result.success) {
                    delay(ACTION_DELAY_MS)
                }

                coroutineContext.ensureActive()
                runContext.awaitContinuation()

                val (nextPageContext, nextPageDiff) = captureObservation()
                json = deepSeekClient.continueAfterStep(
                    apiKey = apiKey,
                    conversation = session,
                    stepFeedback = feedback,
                    pageContext = nextPageContext,
                    pageDiff = nextPageDiff,
                )
            }

            session.status = "max_steps"
            session.finalSummary = "达到最大步数"
            persistMemory(apiKey, session)
            return AgentRunResult(
                false,
                "已达到最大步数（$MAX_AGENT_STEPS），请简化指令或重试",
                logs,
                sessionId = session.sessionId,
            )
        } catch (_: CancellationException) {
            session.status = "cancelled"
            session.finalSummary = "用户已停止"
            persistMemory(apiKey, session)
            return AgentRunResult(
                false,
                "已停止执行",
                logs,
                sessionId = session.sessionId,
            )
        }
    }

    private suspend fun finishAndPersist(
        action: AgentAction,
        loopCommand: String,
        session: AgentConversationSession,
        apiKey: String,
        source: String,
        stepNo: Int,
        logs: MutableList<AgentStepLog>,
        runContext: AgentRunContext,
    ): AgentRunResult {
        val rawSummary = action.message ?: "任务已完成"
        val shouldWait = action.waitingForUser || looksLikeAiQuestion(rawSummary)
        val summary = rawSummary
        val updatedLogs = logs + AgentStepLog(stepNo, action, true, "[$source] $summary")

        if (shouldWait) {
            session.status = "waiting_user"
            session.finalSummary = summary
            pendingUserReply = PendingUserReply(
                originalCommand = extractRootCommand(loopCommand),
                aiPrompt = summary,
                sessionId = session.sessionId,
            )
            return AgentRunResult(
                success = true,
                summary = summary,
                logs = updatedLogs,
                waitingForUserConfirm = true,
                confirmPrompt = summary,
                sessionId = session.sessionId,
            )
        }

        session.status = if (action.finished) "success" else "done"
        session.finalSummary = summary
        persistMemory(apiKey, session)
        runContext.updateProgress(stepNo, "完成")
        return AgentRunResult(true, summary, updatedLogs, sessionId = session.sessionId)
    }

    private suspend fun persistMemory(apiKey: String, session: AgentConversationSession) {
        val store = memoryStore ?: return
        val extracted = deepSeekClient.extractKeyMemory(apiKey, session.buildSessionSummary())
        store.saveFromSession(session, extracted)
    }

    private suspend fun executeLocalSteps(
        service: JoyAccessibilityService,
        steps: List<AgentAction>,
        userCommand: String,
        runContext: AgentRunContext,
    ): AgentRunResult {
        val logs = mutableListOf<AgentStepLog>()
        var stepNo = 0

        for (action in steps) {
            runContext.awaitContinuation()
            if (action.action.equals("finish", ignoreCase = true) || action.finished) {
                val rawSummary = action.message ?: "任务已完成"
                val shouldWait = action.waitingForUser || looksLikeAiQuestion(rawSummary)
                if (shouldWait) {
                    pendingUserReply = PendingUserReply(
                        originalCommand = userCommand,
                        aiPrompt = rawSummary,
                        sessionId = java.util.UUID.randomUUID().toString(),
                    )
                    return AgentRunResult(
                        success = true,
                        summary = rawSummary,
                        logs = logs,
                        waitingForUserConfirm = true,
                        confirmPrompt = rawSummary,
                    )
                }
                return AgentRunResult(true, rawSummary, logs)
            }
            stepNo++
            val result = AgentToolRegistry.execute(service, action)
            logs += AgentStepLog(stepNo, action, result.success, "[本地] ${result.toAgentFeedback()}")
            if (!result.success) {
                return AgentRunResult(false, result.summary, logs)
            }
            if (needsNavigationDelay(action)) delay(NAVIGATION_DELAY_MS) else delay(ACTION_DELAY_MS)
        }
        return AgentRunResult(true, "步骤已执行", logs)
    }

    private fun looksLikeAiQuestion(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return text.contains("？") ||
            text.contains("?") ||
            text.contains("请说") ||
            text.contains("请选择") ||
            text.contains("请确认")
    }

    private fun extractRootCommand(command: String): String {
        return command.lineSequence()
            .firstOrNull { it.trimStart().startsWith("原指令：") }
            ?.removePrefix("原指令：")
            ?.trim()
            ?: command.trim()
    }

    private fun looksLikeOpenChatPage(pageContext: String): Boolean {
        val text = pageContext.lowercase()
        val hasInput = text.contains("可输入(") || text.contains("输入区") || text.contains("[输入区]")
        val hasSend = text.contains("发送相关(") || text.contains("发送")
        val hasChatHint = text.contains("qq") || text.contains("微信") || text.contains("聊天")
        return hasInput && hasSend && hasChatHint
    }

    private fun needsNavigationDelay(action: AgentAction): Boolean {
        return action.action.equals("click", ignoreCase = true) ||
            action.action.equals("back", ignoreCase = true) ||
            action.action.equals("swipe_down", ignoreCase = true)
    }

    companion object {
        private const val MAX_AGENT_STEPS = 30
        private const val ACTION_DELAY_MS = 100L
        private const val NAVIGATION_DELAY_MS = 280L
    }
}
