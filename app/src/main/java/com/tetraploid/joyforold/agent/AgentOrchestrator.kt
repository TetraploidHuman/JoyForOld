package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class AgentOrchestrator(
    private val deepSeekClient: DeepSeekClient = DeepSeekClient(),
    private var memoryStore: AgentMemoryStore? = null,
    private var sessionStore: AgentSessionStore? = null,
) {
    private var pendingState: PendingAgentState? = null

    fun bindMemoryStore(store: AgentMemoryStore) {
        memoryStore = store
    }

    fun bindSessionStore(store: AgentSessionStore) {
        sessionStore = store
        restorePendingFromDisk()
    }

    fun restorePendingFromDisk() {
        if (pendingState != null) return
        pendingState = sessionStore?.loadPending()
    }

    fun peekPendingPrompt(): String? = pendingState?.aiPrompt

    fun hasPendingConfirm(): Boolean = pendingState != null

    fun clearPendingUserReply() {
        pendingState = null
        sessionStore?.clearPending()
    }

    suspend fun run(
        userCommand: String,
        apiKey: String,
        runContext: AgentRunContext = AgentRunContext(),
        onProgress: ((Int, String) -> Unit)? = null,
        resumePendingConfirm: Boolean = false,
    ): AgentRunResult {
        val command = userCommand.trim()
        if (command.isEmpty()) {
            return AgentRunResult(false, "请输入指令", emptyList())
        }

        val service = JoyAccessibilityService.instance
            ?: return AgentRunResult(false, "请先开启无障碍服务", emptyList())

        // 新一轮指令默认开启全新会话；只有“确认续跑”才复用 pending session。
        if (!resumePendingConfirm && pendingState != null) {
            clearPendingUserReply()
        }

        if (resumePendingConfirm) pendingState?.let { pending ->
            val userReply = command.trim()
            pending.session.recordConfirmAnswer(pending.aiPrompt, userReply)
            val enriched = ConfirmResumeBuilder.buildEnrichedResume(
                originalCommand = pending.originalCommand,
                aiPrompt = pending.aiPrompt,
                userReply = userReply,
            )
            pendingState = null
            return runAgentLoop(
                loopCommand = enriched,
                rootCommand = pending.session.rootCommand,
                apiKey = apiKey,
                service = service,
                runContext = runContext,
                onProgress = onProgress,
                existingSession = pending.session,
                initialSnapshot = pending.previousSnapshot,
                resumeAfterUserReply = true,
                resumePending = pending,
            )
        }

        // 1) 先尝试用 AI 在少量预设意图中分类，比如「回家」「求助家人」「紧急呼救」等。
        PresetIntentResolver.resolve(command, apiKey, deepSeekClient)?.let { intentSteps ->
            val intentResult = executeLocalSteps(service, intentSteps, command, runContext)
            if (intentResult.success || intentResult.waitingForUserConfirm) {
                return intentResult
            }
        }

        // 2) 命中失败时再尝试纯规则的老人高频模板。
        ElderTaskTemplateMatcher.match(command)?.let { templateSteps ->
            val templateResult = executeLocalSteps(service, templateSteps, command, runContext)
            if (templateResult.success || templateResult.waitingForUserConfirm) {
                return templateResult
            }
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
        existingSession: AgentConversationSession? = null,
        initialSnapshot: StructuredPageSnapshot? = null,
        resumeAfterUserReply: Boolean = false,
        resumePending: PendingAgentState? = null,
    ): AgentRunResult {
        val logs = mutableListOf<AgentStepLog>()
        val session = existingSession ?: AgentConversationSession(
            rootCommand = extractRootCommand(rootCommand),
        )
        val memories = memoryStore?.loadRecentMemories().orEmpty()
        val memoryPrompt = memoryStore
            ?.formatMemoriesForPrompt(memories, currentCommand = extractRootCommand(rootCommand))
            .orEmpty()
        var previousSnapshot: StructuredPageSnapshot? = initialSnapshot
        var stepNo = session.stepRecords.size

        suspend fun captureObservation(): Triple<String, String, String> {
            val snapshots = service.captureStructuredSnapshots()
            val merged = service.mergeSnapshots(snapshots)
            if (merged == null) {
                return Triple(
                    "无法读取页面，请切换到目标应用。",
                    "无法读取页面",
                    "无法读取页面",
                )
            }
            val pageContext = merged.toCompactSummary()
            val pageDiff = PageObservation.diff(previousSnapshot, merged)
            previousSnapshot = merged
            return Triple(pageContext, pageDiff, merged.toMinimalSummary())
        }

        try {
            var json = if (resumeAfterUserReply) {
                deepSeekClient.ensureSystemSeeded(session, memoryPrompt)
                val (pageContext, pageDiff, minimalPageContext) = captureObservation()
                runContext.awaitContinuation()
                deepSeekClient.continueAfterStep(
                    apiKey = apiKey,
                    conversation = session,
                    stepFeedback = "【用户已回答，请继续任务】\n$loopCommand",
                    pageContext = pageContext,
                    pageDiff = pageDiff,
                    keyMemories = memoryPrompt,
                    minimalPageContext = minimalPageContext,
                )
            } else {
                val (pageContext, pageDiff, minimalPageContext) = captureObservation()
                val effectiveCommand = if (loopCommand != rootCommand) loopCommand else session.rootCommand
                runContext.awaitContinuation()
                deepSeekClient.beginTask(
                    apiKey = apiKey,
                    conversation = session,
                    userCommand = effectiveCommand,
                    pageContext = pageContext,
                    pageDiff = pageDiff,
                    keyMemories = memoryPrompt,
                    minimalPageContext = minimalPageContext,
                )
            }

            repeat(MAX_AGENT_STEPS) {
                coroutineContext.ensureActive()
                runContext.awaitContinuation()

                stepNo++
                runContext.updateProgress(stepNo, "规划第 $stepNo 步")
                onProgress?.invoke(stepNo, runContext.statusMessage)

                var action = AgentAction.fromJson(json)

                AgentActionGuard.sensitiveConfirmOverride(session, action)?.let { override ->
                    action = override
                }

                if (action.action.equals("finish", ignoreCase = true) || action.finished) {
                    val currentSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots())
                        ?: previousSnapshot
                    AgentFinishGuard.prematureFinishReason(
                        session = session,
                        action = action,
                        snapshot = currentSnapshot,
                        rootCommand = session.rootCommand,
                    )?.let { blockReason ->
                        logs += AgentStepLog(
                            step = stepNo,
                            action = action,
                            success = false,
                            detail = "[Agent] $blockReason",
                        )
                        session.recordStep(
                            stepNo,
                            action,
                            ActionExecutionResult(false, "过早结束", detail = blockReason),
                            "",
                        )
                        val (pageContext, pageDiff, minimalPageContext) = captureObservation()
                        json = deepSeekClient.continueAfterStep(
                            apiKey = apiKey,
                            conversation = session,
                            stepFeedback = "【系统阻止过早结束】\n$blockReason",
                            pageContext = pageContext,
                            pageDiff = pageDiff,
                            keyMemories = memoryPrompt,
                            minimalPageContext = minimalPageContext,
                        )
                        return@repeat
                    }
                    return finishAndPersist(
                        action = action,
                        userCommand = extractRootCommand(rootCommand),
                        session = session,
                        apiKey = apiKey,
                        source = if (resumeAfterUserReply) "Agent续跑" else "Agent",
                        stepNo = stepNo,
                        logs = logs,
                        runContext = runContext,
                        previousSnapshot = previousSnapshot,
                    )
                }

                runContext.updateProgress(stepNo, "执行：${action.action}")
                onProgress?.invoke(stepNo, runContext.statusMessage)

                AgentActionGuard.blockedRepeatReason(session, action)?.let { blockReason ->
                    val blockResult = ActionExecutionResult(
                        success = false,
                        summary = "已阻止重复操作",
                        detail = blockReason,
                    )
                    logs += AgentStepLog(
                        step = stepNo,
                        action = action,
                        success = false,
                        detail = "[Agent] $blockReason",
                    )
                    session.recordStep(stepNo, action, blockResult, "")
                    val (pageContext, pageDiff, minimalPageContext) = captureObservation()
                    json = deepSeekClient.continueAfterStep(
                        apiKey = apiKey,
                        conversation = session,
                        stepFeedback = "【系统阻止重复操作】\n$blockReason",
                        pageContext = pageContext,
                        pageDiff = pageDiff,
                        keyMemories = memoryPrompt,
                        minimalPageContext = minimalPageContext,
                    )
                    return@repeat
                }

                val result = AgentToolRegistry.execute(service, action)

                if (needsNavigationDelay(action)) {
                    delay(NAVIGATION_DELAY_MS)
                } else if (result.success) {
                    delay(ACTION_DELAY_MS)
                }

                val (pageContext, pageDiff, minimalPageContext) = captureObservation()
                logs += AgentStepLog(
                    step = stepNo,
                    action = action,
                    success = result.success,
                    detail = "[Agent] ${result.toAgentFeedback()}",
                )
                session.recordStep(stepNo, action, result, pageDiff)

                val feedback = buildString {
                    appendLine("【上一步执行结果】")
                    appendLine(
                        "操作：${action.action}" +
                            action.targetText?.let { " target=\"$it\"" }.orEmpty() +
                            action.inputText?.let { " input=\"$it\"" }.orEmpty(),
                    )
                    append(result.toAgentFeedback())
                    AgentStepAdvisor.postStepHint(
                        session = session,
                        action = action,
                        result = result,
                        rootCommand = session.rootCommand,
                        snapshot = previousSnapshot,
                    )?.let { hint ->
                        appendLine()
                        append(hint)
                    }
                }

                coroutineContext.ensureActive()
                runContext.awaitContinuation()

                json = deepSeekClient.continueAfterStep(
                    apiKey = apiKey,
                    conversation = session,
                    stepFeedback = feedback,
                    pageContext = pageContext,
                    pageDiff = pageDiff,
                    keyMemories = memoryPrompt,
                    minimalPageContext = minimalPageContext,
                )
            }

            session.status = "max_steps"
            session.finalSummary = "达到最大步数"
            val maxStepResult = AgentRunResult(
                false,
                "已达到最大步数（$MAX_AGENT_STEPS），请简化指令或重试",
                logs,
                sessionId = session.sessionId,
            )
            if (resumePending != null) {
                restorePendingAfterFailedResume(
                    resumePending,
                    session,
                    previousSnapshot,
                )
            }
            return maxStepResult
        } catch (_: CancellationException) {
            session.status = "cancelled"
            session.finalSummary = "用户已停止"
            val cancelResult = AgentRunResult(
                false,
                "已停止执行",
                logs,
                sessionId = session.sessionId,
            )
            if (resumePending != null) {
                restorePendingAfterFailedResume(
                    resumePending,
                    session,
                    previousSnapshot,
                )
            }
            return cancelResult
        } catch (error: Exception) {
            session.status = "failed"
            session.finalSummary = error.message ?: "AI 请求失败"
            val failResult = AgentRunResult(
                false,
                error.message ?: "AI 请求失败",
                logs,
                sessionId = session.sessionId,
            )
            if (resumePending != null) {
                restorePendingAfterFailedResume(
                    resumePending,
                    session,
                    previousSnapshot,
                )
            }
            return failResult
        }
    }

    private fun restorePendingAfterFailedResume(
        original: PendingAgentState,
        session: AgentConversationSession,
        previousSnapshot: StructuredPageSnapshot?,
    ) {
        savePendingState(
            original.copy(
                session = session,
                previousSnapshot = previousSnapshot ?: original.previousSnapshot,
            ),
        )
    }

    private fun savePendingState(state: PendingAgentState) {
        pendingState = state
        sessionStore?.savePending(state)
    }

    private suspend fun finishAndPersist(
        action: AgentAction,
        userCommand: String,
        session: AgentConversationSession,
        apiKey: String,
        source: String,
        stepNo: Int,
        logs: MutableList<AgentStepLog>,
        runContext: AgentRunContext,
        previousSnapshot: StructuredPageSnapshot?,
    ): AgentRunResult {
        val rawSummary = action.message ?: "任务已完成"
        val shouldWait = action.waitingForUser || looksLikeAiQuestion(rawSummary)
        val updatedLogs = logs + AgentStepLog(stepNo, action, true, "[$source] $rawSummary")

        if (shouldWait) {
            session.status = "waiting_user"
            session.finalSummary = rawSummary
            val state = PendingAgentState(
                originalCommand = userCommand,
                aiPrompt = rawSummary,
                session = session,
                previousSnapshot = previousSnapshot,
            )
            savePendingState(state)
            return AgentRunResult(
                success = true,
                summary = rawSummary,
                logs = updatedLogs,
                waitingForUserConfirm = true,
                confirmPrompt = rawSummary,
                sessionId = session.sessionId,
            )
        }

        session.status = if (action.finished) "success" else "done"
        session.finalSummary = rawSummary
        persistMemoryIfWorthy(apiKey, session)
        clearPendingUserReply()
        runContext.updateProgress(stepNo, "完成")
        return AgentRunResult(true, rawSummary, updatedLogs, sessionId = session.sessionId)
    }

    private suspend fun persistMemoryIfWorthy(apiKey: String, session: AgentConversationSession) {
        if (session.status != "success" && session.status != "done") return
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
        val memoryPrompt = memoryStore?.formatMemoriesForPrompt(
            memoryStore?.loadRecentMemories().orEmpty(),
            currentCommand = userCommand,
        ).orEmpty()
        val localSession = AgentConversationSession(rootCommand = userCommand)

        for (action in steps) {
            runContext.awaitContinuation()
            if (action.action.equals("finish", ignoreCase = true) || action.finished) {
                val rawSummary = action.message ?: "任务已完成"
                val shouldWait = action.waitingForUser || looksLikeAiQuestion(rawSummary)
                if (shouldWait) {
                    deepSeekClient.ensureSystemSeeded(localSession, memoryPrompt)
                    localSession.addUser("【用户指令】$userCommand")
                    localSession.appendLocalStepsSummary(logs)
                    val state = PendingAgentState(
                        originalCommand = userCommand,
                        aiPrompt = rawSummary,
                        session = localSession,
                        previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),
                    )
                    savePendingState(state)
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
            localSession.recordStep(stepNo, action, result, "")
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
            action.action.equals("swipe_down", ignoreCase = true) ||
            action.action.equals("open_app", ignoreCase = true)
    }

    companion object {
        private const val MAX_AGENT_STEPS = 30
        private const val ACTION_DELAY_MS = 100L
        private const val NAVIGATION_DELAY_MS = 280L
    }
}
