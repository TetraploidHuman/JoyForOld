package com.tetraploid.joyforold.agent



import android.content.Context

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService

import com.tetraploid.joyforold.preset.PresetCommandStore

import com.tetraploid.joyforold.privacy.PageContextRedactor

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.delay

import kotlinx.coroutines.ensureActive

import kotlin.coroutines.coroutineContext

import org.json.JSONObject



class AgentOrchestrator(

    private val deepSeekClient: DeepSeekClient = DeepSeekClient(),

    private var memoryStore: AgentMemoryStore? = null,

    private var sessionStore: AgentSessionStore? = null,

    private var appHintStore: AppHintStore? = null,

    private var presetStore: PresetCommandStore? = null,

) {

    private var pendingState: PendingAgentState? = null



    fun bindMemoryStore(store: AgentMemoryStore) {

        memoryStore = store

    }



    fun bindSessionStore(store: AgentSessionStore) {

        sessionStore = store

        restorePendingFromDisk()

    }



    fun bindAppHintStore(store: AppHintStore) {

        appHintStore = store

    }



    fun bindPresetStore(store: PresetCommandStore) {

        presetStore = store

    }



    fun restorePendingFromDisk() {

        if (pendingState != null) return

        pendingState = sessionStore?.loadPending()

    }



    fun peekPendingPrompt(): String? = pendingState?.aiPrompt



    fun peekPendingKind(): PendingKind = pendingState?.kind ?: PendingKind.USER_CONFIRM

    fun peekPendingNeedsBinaryConfirm(): Boolean = pendingState?.needsBinaryConfirm ?: false



    fun hasPendingConfirm(): Boolean = pendingState != null



    fun clearPendingUserReply() {

        pendingState = null

        sessionStore?.clearPending()

    }



    suspend fun run(

        userCommand: String,

        apiKey: String,

        appContext: Context? = null,

        runContext: AgentRunContext = AgentRunContext(),

        onProgress: ((Int, String) -> Unit)? = null,

        resumePendingConfirm: Boolean = false,

    ): AgentRunResult {

        val command = userCommand.trim()

        if (command.isEmpty()) {

            return AgentRunResult(false, "请输入指令", emptyList())

        }



        val service = JoyAccessibilityService.instance

        val executionContext = service ?: appContext

        if (executionContext == null) {

            return AgentRunResult(

                false,

                "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

                emptyList(),

            )

        }



        if (service == null && pendingState != null) {

            return AgentRunResult(

                false,

                "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

                emptyList(),

            )

        }



        if (service == null) {

            return runSystemIntentOnly(command, apiKey, executionContext, runContext, onProgress)

        }



        if (pendingState?.kind == PendingKind.TASK_ABANDON) {

            return handleTaskAbandonReply(command, apiKey, service, runContext, onProgress)

        }



        if (resumePendingConfirm) {

            pendingState?.let { pending ->

                return when (pending.kind) {

                    PendingKind.ROUTE_CLARIFY -> handleRouteClarifyReply(

                        pending, command, service, runContext,

                    )

                    PendingKind.TASK_ABANDON -> handleTaskAbandonReply(

                        command, apiKey, service, runContext, onProgress,

                    )

                    PendingKind.USER_CONFIRM -> resumeUserConfirm(

                        pending, command, apiKey, service, runContext, onProgress,

                    )

                }

            }

        }



        if (pendingState != null) {

            return promptTaskAbandon(command, service)

        }



        val presets = presetStore?.loadPresets().orEmpty()

        CommandRouteResolver.resolve(command, apiKey, deepSeekClient, presets, appContext = executionContext)?.let { route ->

            route.clarifyMessage?.let { clarify ->

                return saveRouteClarifyPending(command, clarify, route.steps, service)

            }

            val routeResult = executeLocalSteps(executionContext, service, route.steps, command, runContext)

            if (routeResult.success || routeResult.waitingForUserConfirm) {

                return routeResult

            }

        }



        if (LocalCommandParser.parse(command) != null &&

            LocalCommandParser.isSendToSpecificPerson(command)

        ) {

            val pageContext = service.snapshotCompactForAgent()

            if (looksLikeOpenChatPage(pageContext)) {

                return runAgentLoop(command, command, apiKey, service, runContext, onProgress)

            }

        }



        return runAgentLoop(command, command, apiKey, service, runContext, onProgress)

    }



    private suspend fun resumeUserConfirm(

        pending: PendingAgentState,

        command: String,

        apiKey: String,

        service: JoyAccessibilityService,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

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



    private suspend fun handleRouteClarifyReply(

        pending: PendingAgentState,

        command: String,

        service: JoyAccessibilityService,

        runContext: AgentRunContext,

    ): AgentRunResult {

        return when (VoiceConfirmPhraseMatcher.classify(command)) {

            VoiceConfirmPhraseMatcher.Intent.CONFIRM -> {

                val steps = pending.plannedSteps.orEmpty()

                pendingState = null

                sessionStore?.clearPending()

                if (steps.isEmpty()) {

                    AgentRunResult(false, "没有可执行的预设步骤", emptyList())

                } else {

                    executeLocalSteps(service, service, steps, pending.originalCommand, runContext)

                }

            }

            VoiceConfirmPhraseMatcher.Intent.CANCEL -> {

                clearPendingUserReply()

                AgentRunResult(true, "好的，已取消", emptyList())

            }

            VoiceConfirmPhraseMatcher.Intent.UNCLEAR -> {

                AgentRunResult(

                    success = true,

                    summary = pending.aiPrompt,

                    logs = emptyList(),

                    waitingForUserConfirm = true,

                    confirmPrompt = pending.aiPrompt,

                )

            }

        }

    }



    private suspend fun handleTaskAbandonReply(

        command: String,

        apiKey: String,

        service: JoyAccessibilityService,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

        val pending = pendingState ?: return AgentRunResult(false, "没有待处理任务", emptyList())

        return when (PendingAbandonPhraseMatcher.classify(command)) {

            PendingAbandonPhraseMatcher.Intent.ABANDON -> {

                val deferred = pending.deferredCommand?.trim().orEmpty()

                clearPendingUserReply()

                if (deferred.isBlank()) {

                    AgentRunResult(true, "已放弃未完成任务", emptyList())

                } else {

                    run(
                        userCommand = deferred,
                        apiKey = apiKey,
                        appContext = service,
                        runContext = runContext,
                        onProgress = onProgress,
                        resumePendingConfirm = false,
                    )

                }

            }

            PendingAbandonPhraseMatcher.Intent.CONTINUE -> {

                val restored = PendingAgentState(

                    originalCommand = pending.suspendedOriginalCommand ?: pending.originalCommand,

                    aiPrompt = pending.suspendedAiPrompt ?: pending.aiPrompt,

                    session = pending.suspendedSession ?: pending.session,

                    previousSnapshot = pending.suspendedSnapshot ?: pending.previousSnapshot,

                    kind = PendingKind.USER_CONFIRM,

                    needsBinaryConfirm = pending.suspendedNeedsBinaryConfirm ?: false,

                )

                savePendingState(restored)

                AgentRunResult(

                    success = true,

                    summary = restored.aiPrompt,

                    logs = emptyList(),

                    waitingForUserConfirm = true,

                    confirmPrompt = restored.aiPrompt,

                    needsBinaryConfirm = restored.needsBinaryConfirm,

                )

            }

            PendingAbandonPhraseMatcher.Intent.UNCLEAR -> {

                AgentRunResult(

                    success = true,

                    summary = pending.aiPrompt,

                    logs = emptyList(),

                    waitingForUserConfirm = true,

                    confirmPrompt = pending.aiPrompt,

                )

            }

        }

    }



    private fun promptTaskAbandon(

        newCommand: String,

        service: JoyAccessibilityService,

    ): AgentRunResult {

        val existing = pendingState ?: return AgentRunResult(false, "没有待处理任务", emptyList())

        val prompt = "您有未完成的任务：${existing.aiPrompt.take(80)}。要放弃并开始新指令吗？请说「放弃」或「继续」。"

        val abandonState = PendingAgentState(

            originalCommand = newCommand,

            aiPrompt = prompt,

            session = existing.session,

            previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),

            kind = PendingKind.TASK_ABANDON,

            deferredCommand = newCommand,

            suspendedOriginalCommand = existing.originalCommand,

            suspendedAiPrompt = existing.aiPrompt,

            suspendedSession = existing.session,

            suspendedSnapshot = existing.previousSnapshot,

            suspendedNeedsBinaryConfirm = existing.needsBinaryConfirm,

        )

        savePendingState(abandonState)

        return AgentRunResult(

            success = true,

            summary = prompt,

            logs = emptyList(),

            waitingForUserConfirm = true,

            confirmPrompt = prompt,

        )

    }



    private fun saveRouteClarifyPending(

        command: String,

        clarifyMessage: String,

        steps: List<AgentAction>,

        service: JoyAccessibilityService,

    ): AgentRunResult {

        val session = AgentConversationSession(rootCommand = command)

        val state = PendingAgentState(

            originalCommand = command,

            aiPrompt = clarifyMessage,

            session = session,

            previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),

            kind = PendingKind.ROUTE_CLARIFY,

            plannedSteps = steps,

        )

        savePendingState(state)

        return AgentRunResult(

            success = true,

            summary = clarifyMessage,

            logs = emptyList(),

            waitingForUserConfirm = true,

            confirmPrompt = clarifyMessage,

        )

    }



    private sealed class GuardOutcome {

        data class Blocked(val reason: String) : GuardOutcome()

        data class NeedsConfirm(val action: AgentAction) : GuardOutcome()

        data class Executed(val result: ActionExecutionResult) : GuardOutcome()

    }



    private suspend fun executeGuardedAction(

        context: Context,

        service: JoyAccessibilityService?,

        session: AgentConversationSession,

        action: AgentAction,

        snapshot: StructuredPageSnapshot?,

    ): GuardOutcome {

        AgentActionWhitelist.blockReason(action.action)?.let { return GuardOutcome.Blocked(it) }



        AgentActionGuard.sensitiveConfirmOverride(session, action)?.let {

            return GuardOutcome.NeedsConfirm(it)

        }



        AgentActionGuard.blockedRepeatReason(session, action)?.let { return GuardOutcome.Blocked(it) }



        val currentSnapshot = snapshot ?: service?.mergeSnapshots(service.captureStructuredSnapshots())

        RiskScreenGuard.blockReason(currentSnapshot, action)?.let { return GuardOutcome.Blocked(it) }



        if (service == null && !AgentToolRegistry.isSystemIntentAction(action.action)) {

            return GuardOutcome.Blocked("需要无障碍服务才能执行：${action.action}")

        }



        val result = AgentToolRegistry.executeSystemIntent(context, action)

            ?: service!!.executeWithResult(action)

        return GuardOutcome.Executed(result)

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

        val pageContextNeed = IntentCapabilityMatrix.inferPageContextNeed(loopCommand)

        val agentToolsPrompt = IntentCapabilityMatrix.toolsPromptForContext(pageContextNeed)



        suspend fun captureObservation(): PageObservationPayload {

            if (pageContextNeed == IntentCapabilityMatrix.PageContextNeed.NONE) {

                return PageObservationPayload(

                    pageContext = "",

                    pageDiff = "",

                    minimalPageContext = "",

                    mode = PageContextMode.NONE,

                )

            }

            val snapshots = service.captureStructuredSnapshots()

            val merged = service.mergeSnapshots(snapshots) ?: return PageObservationPayload(

                pageContext = "无法读取页面，请切换到目标应用。",

                pageDiff = "无法读取页面",

                minimalPageContext = "无法读取页面",

                mode = PageContextMode.FULL,

            )

            val enriched = enrichWithAppHints(merged)

            val pageDiff = PageContextRedactor.redact(PageObservation.diff(previousSnapshot, enriched))

            val dynamicMode = PageContextSelector.modeFor(previousSnapshot, enriched, pageDiff)

            previousSnapshot = enriched

            val mode = IntentCapabilityMatrix.pageContextModeForNeed(pageContextNeed, dynamicMode)

            return PageObservationPayload(

                pageContext = PageContextRedactor.redact(enriched.toCompactSummary()),

                pageDiff = pageDiff,

                minimalPageContext = PageContextRedactor.redact(enriched.toMinimalSummary()),

                mode = mode,

            )

        }



        suspend fun continuePlanning(

            stepFeedback: String,

        ): JSONObject {

            val observation = captureObservation()

            return deepSeekClient.continueAfterStep(

                apiKey = apiKey,

                conversation = session,

                stepFeedback = stepFeedback,

                pageContext = observation.pageContext,

                pageDiff = observation.pageDiff,

                keyMemories = memoryPrompt,

                minimalPageContext = observation.minimalPageContext,

                pageContextMode = observation.mode,

            )

        }



        try {

            var json = if (resumeAfterUserReply) {

                deepSeekClient.ensureSystemSeeded(session, memoryPrompt)

                val observation = captureObservation()

                runContext.awaitContinuation()

                deepSeekClient.continueAfterStep(

                    apiKey = apiKey,

                    conversation = session,

                    stepFeedback = "【用户已回答，请继续任务】\n$loopCommand",

                    pageContext = observation.pageContext,

                    pageDiff = observation.pageDiff,

                    keyMemories = memoryPrompt,

                    minimalPageContext = observation.minimalPageContext,

                    pageContextMode = observation.mode,

                )

            } else {

                val observation = captureObservation()

                val effectiveCommand = if (loopCommand != rootCommand) loopCommand else session.rootCommand

                runContext.awaitContinuation()

                deepSeekClient.beginTask(

                    apiKey = apiKey,

                    conversation = session,

                    userCommand = effectiveCommand,

                    pageContext = observation.pageContext,

                    pageDiff = observation.pageDiff,

                    keyMemories = memoryPrompt,

                    minimalPageContext = observation.minimalPageContext,

                    pageContextMode = observation.mode,

                    toolsPrompt = agentToolsPrompt,

                )

            }



            val actionQueue = ArrayDeque<AgentAction>()



            repeat(MAX_AGENT_STEPS) {

                coroutineContext.ensureActive()

                runContext.awaitContinuation()



                if (actionQueue.isEmpty()) {

                    actionQueue.addAll(AgentPlanParser.parsePlan(json))

                    if (actionQueue.isEmpty()) return@repeat

                }



                stepNo++

                var action = actionQueue.removeFirst()



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

                        actionQueue.clear()

                        json = continuePlanning("【系统阻止过早结束】\n$blockReason")

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



                runContext.updateProgress(

                    stepNo,

                    if (actionQueue.isNotEmpty()) "执行：${action.action}（续）" else "执行：${action.action}",

                )

                onProgress?.invoke(stepNo, runContext.statusMessage)



                when (val outcome = executeGuardedAction(service, service, session, action, previousSnapshot)) {

                    is GuardOutcome.Blocked -> {

                        val blockReason = outcome.reason

                        val blockResult = ActionExecutionResult(

                            success = false,

                            summary = "操作被拦截",

                            detail = blockReason,

                        )

                        logs += AgentStepLog(

                            step = stepNo,

                            action = action,

                            success = false,

                            detail = "[Agent] $blockReason",

                        )

                        session.recordStep(stepNo, action, blockResult, "")

                        actionQueue.clear()

                        val feedbackTag = if (blockReason.contains("重复")) {

                            "【系统阻止重复操作】"

                        } else if (blockReason.contains("白名单")) {

                            "【系统拒绝未知动作】"

                        } else {

                            "【系统拦截高风险操作】"

                        }

                        json = continuePlanning("$feedbackTag\n$blockReason")

                    }

                    is GuardOutcome.NeedsConfirm -> {

                        return finishAndPersist(

                            action = outcome.action,

                            userCommand = extractRootCommand(rootCommand),

                            session = session,

                            apiKey = apiKey,

                            source = "Agent确认",

                            stepNo = stepNo,

                            logs = logs,

                            runContext = runContext,

                            previousSnapshot = previousSnapshot,

                        )

                    }

                    is GuardOutcome.Executed -> {

                        val result = outcome.result

                        if (needsNavigationDelay(action)) {

                            delay(NAVIGATION_DELAY_MS)

                        } else if (result.success) {

                            delay(ACTION_DELAY_MS)

                        }



                        val observation = captureObservation()

                        logs += AgentStepLog(

                            step = stepNo,

                            action = action,

                            success = result.success,

                            detail = "[Agent] ${result.toAgentFeedback()}",

                        )

                        session.recordStep(stepNo, action, result, observation.pageDiff)

                        AppHintLearner.maybeLearn(

                            store = appHintStore,

                            packageName = previousSnapshot?.packageName.orEmpty(),

                            action = action,

                            success = result.success,

                        )



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



                        if (

                            actionQueue.isNotEmpty() &&

                            result.success &&

                            !AgentPlanParser.stopsBatchAfter(action) &&

                            observation.mode != PageContextMode.FULL

                        ) {

                            return@repeat

                        }



                        actionQueue.clear()



                        coroutineContext.ensureActive()

                        runContext.awaitContinuation()



                        json = continuePlanning(feedback)

                    }

                }

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

        val shouldWait = action.waitingForUser

        val updatedLogs = logs + AgentStepLog(stepNo, action, true, "[$source] $rawSummary")



        if (shouldWait) {

            session.status = "waiting_user"

            session.finalSummary = rawSummary

            val state = PendingAgentState(

                originalCommand = userCommand,

                aiPrompt = rawSummary,

                session = session,

                previousSnapshot = previousSnapshot,

                kind = PendingKind.USER_CONFIRM,

                needsBinaryConfirm = action.needsBinaryConfirm,

            )

            savePendingState(state)

            return AgentRunResult(

                success = true,

                summary = rawSummary,

                logs = updatedLogs,

                waitingForUserConfirm = true,

                confirmPrompt = rawSummary,

                needsBinaryConfirm = action.needsBinaryConfirm,

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



    private suspend fun runSystemIntentOnly(

        command: String,

        apiKey: String,

        context: Context,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

        val presets = presetStore?.loadPresets().orEmpty()

        CommandRouteResolver.resolve(command, apiKey, deepSeekClient, presets, appContext = context)?.let { route ->

            if (!AgentToolRegistry.isSystemIntentOnly(route.steps)) {

                return AgentRunResult(

                    false,

                    "此操作需要无障碍服务，请回到应用开启后再试",

                    emptyList(),

                )

            }

            route.clarifyMessage?.let { clarify ->

                return AgentRunResult(

                    false,

                    clarify,

                    emptyList(),

                )

            }

            return executeLocalSteps(context, service = null, route.steps, command, runContext)

        }

        return AgentRunResult(

            false,

            "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

            emptyList(),

        )

    }



    private suspend fun executeLocalSteps(

        context: Context,

        service: JoyAccessibilityService?,

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

        var previousSnapshot = service?.mergeSnapshots(service.captureStructuredSnapshots())

        var lastInfoSummary: String? = null



        for (action in steps) {

            runContext.awaitContinuation()

            if (action.action.equals("finish", ignoreCase = true) || action.finished) {

                val rawSummary = lastInfoSummary ?: action.message ?: "任务已完成"

                val shouldWait = action.waitingForUser

                if (shouldWait) {

                    deepSeekClient.ensureSystemSeeded(localSession, memoryPrompt)

                    localSession.addUser("【用户指令】$userCommand")

                    localSession.appendLocalStepsSummary(logs)

                    val state = PendingAgentState(

                        originalCommand = userCommand,

                        aiPrompt = rawSummary,

                        session = localSession,

                        previousSnapshot = previousSnapshot,

                        kind = PendingKind.USER_CONFIRM,

                        needsBinaryConfirm = action.needsBinaryConfirm,

                    )

                    savePendingState(state)

                    return AgentRunResult(

                        success = true,

                        summary = rawSummary,

                        logs = logs,

                        waitingForUserConfirm = true,

                        confirmPrompt = rawSummary,

                        needsBinaryConfirm = action.needsBinaryConfirm,

                    )

                }

                return AgentRunResult(true, rawSummary, logs)

            }



            stepNo++

            when (val outcome = executeGuardedAction(context, service, localSession, action, previousSnapshot)) {

                is GuardOutcome.NeedsConfirm -> {

                    val confirmAction = outcome.action

                    val rawSummary = confirmAction.message ?: "请确认是否继续"

                    deepSeekClient.ensureSystemSeeded(localSession, memoryPrompt)

                    localSession.addUser("【用户指令】$userCommand")

                    localSession.appendLocalStepsSummary(logs)

                    val state = PendingAgentState(

                        originalCommand = userCommand,

                        aiPrompt = rawSummary,

                        session = localSession,

                        previousSnapshot = previousSnapshot,

                        kind = PendingKind.USER_CONFIRM,

                        needsBinaryConfirm = confirmAction.needsBinaryConfirm,

                    )

                    savePendingState(state)

                    return AgentRunResult(

                        success = true,

                        summary = rawSummary,

                        logs = logs,

                        waitingForUserConfirm = true,

                        confirmPrompt = rawSummary,

                        needsBinaryConfirm = confirmAction.needsBinaryConfirm,

                    )

                }

                is GuardOutcome.Blocked -> {

                    logs += AgentStepLog(

                        step = stepNo,

                        action = action,

                        success = false,

                        detail = "[本地] ${outcome.reason}",

                    )

                    return AgentRunResult(false, outcome.reason, logs)

                }

                is GuardOutcome.Executed -> {

                    val result = outcome.result

                    logs += AgentStepLog(stepNo, action, result.success, "[本地] ${result.toAgentFeedback()}")

                    localSession.recordStep(stepNo, action, result, "")

                    previousSnapshot = service?.mergeSnapshots(service.captureStructuredSnapshots())

                        ?: previousSnapshot

                    if (!result.success) {

                        return AgentRunResult(false, result.summary, logs)

                    }

                    if (action.action in INFO_QUERY_ACTIONS) {
                        lastInfoSummary = result.summary
                    }

                    if (needsNavigationDelay(action)) delay(NAVIGATION_DELAY_MS) else delay(ACTION_DELAY_MS)

                }

            }

        }

        return AgentRunResult(true, "步骤已执行", logs)

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



    private fun enrichWithAppHints(snapshot: StructuredPageSnapshot): StructuredPageSnapshot {

        val stored = appHintStore?.formatForPrompt(snapshot.packageName).orEmpty()

        if (stored.isBlank()) return snapshot

        val combined = listOf(snapshot.appHint, stored).filter { it.isNotBlank() }.joinToString("\n")

        return snapshot.copy(appHint = combined)

    }



    companion object {

        private const val MAX_AGENT_STEPS = 30

        private const val ACTION_DELAY_MS = 100L

        private const val NAVIGATION_DELAY_MS = 280L

        private val INFO_QUERY_ACTIONS = setOf(
            "tell_time",
            "query_weather",
            "read_unread_messages",
        )

    }

}


