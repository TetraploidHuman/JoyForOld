package com.tetraploid.joyforold.agent



import android.content.Context

import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.accessibility.AccessibilityGateways

import com.tetraploid.joyforold.app.InstalledAppResolver

import com.tetraploid.joyforold.preset.PresetCommandStore

import com.tetraploid.joyforold.privacy.PageContextRedactor

import com.tetraploid.joyforold.overlay.VisionOverlayGuard

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.delay

import kotlinx.coroutines.ensureActive

import kotlin.coroutines.coroutineContext

import org.json.JSONObject



class AgentOrchestrator(

    private val llmClient: AgentLlmClient,

    private val memoryStore: AgentMemoryStore,

    sessionStore: AgentSessionStore,

    private val appHintStore: AppHintStore,

    private val presetStore: PresetCommandStore,

    private val visionDebugStore: VisionDebugStore,

    private val contextConsentStore: ContextConsentStore,

) {

    private val pendingMachine = PendingStateMachine()
    init {
        pendingMachine.bindSessionStore(sessionStore)
        pendingMachine.restoreFromDisk()
    }

    private val pendingExecutor = object : PendingExecutor {
        override suspend fun resumeUserConfirm(
            pending: PendingAgentState,
            command: String,
            apiKey: String,
            service: AccessibilityGateway,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = resumeUserConfirm(pending, command, apiKey, service, runContext, onProgress)

        override suspend fun executeLocalSteps(
            context: Context,
            service: AccessibilityGateway,
            steps: List<AgentAction>,
            originalCommand: String,
            runContext: AgentRunContext,
        ): AgentRunResult {
            val result = executeLocalSteps(context, service, steps, originalCommand, runContext)
            if (result.success && !result.waitingForUserConfirm && LocalFastPathGuard.isUndoable(steps)) {
                LocalUndoRegistry.register(steps)
            }
            return result
        }

        override suspend fun runNewCommand(
            command: String,
            apiKey: String,
            service: AccessibilityGateway,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = run(
            userCommand = command,
            apiKey = apiKey,
            appContext = service.context(),
            runContext = runContext,
            onProgress = onProgress,
            resumePendingConfirm = false,
        )

        override suspend fun runDisambiguatedIntent(
            command: String,
            intentId: String,
            apiKey: String,
            appContext: Context,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = this@AgentOrchestrator.runDisambiguatedIntent(
            command, intentId, apiKey, appContext, runContext, onProgress,
        )
    }



    fun restorePendingFromDisk() {

        pendingMachine.restoreFromDisk()

    }



    fun peekPendingPrompt(): String? = pendingMachine.peekPendingPrompt()



    fun peekPendingKind(): PendingKind = pendingMachine.peekPendingKind()

    fun peekPendingOriginalCommand(): String? = pendingMachine.peekPendingOriginalCommand()

    fun peekPendingNeedsBinaryConfirm(): Boolean = pendingMachine.peekPendingNeedsBinaryConfirm()

    fun peekDisambiguationOptions(): List<DisambiguationOption> =
        pendingMachine.peekDisambiguationOptions()



    fun hasPendingConfirm(): Boolean = pendingMachine.hasPending()



    fun clearPendingUserReply() {

        pendingMachine.clear()

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



        val service = AccessibilityGateways.current

        val executionContext = service?.context() ?: appContext

        if (executionContext == null) {

            return AgentRunResult(

                false,

                "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

                emptyList(),

            )

        }



        if (service == null && pendingMachine.hasPending()) {

            return AgentRunResult(

                false,

                "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

                emptyList(),

            )

        }



        if (service == null) {

            return runSystemIntentOnly(command, apiKey, executionContext, runContext, onProgress)

        }



        if (pendingMachine.isTaskAbandonKind()) {

            return pendingMachine.handleTaskAbandonReply(

                command, apiKey, service, runContext, onProgress, pendingExecutor,

            )

        }



        if (resumePendingConfirm) {

            pendingMachine.current()?.let { pending ->

                return pendingMachine.resumePending(

                    pending, command, apiKey, service, runContext, onProgress, pendingExecutor,

                )

            }

        }



        if (pendingMachine.hasPending()) {

            return pendingMachine.promptTaskAbandon(command, service)

        }



        val presets = presetStore.loadPresets()

        IntentDisambiguationHelper.peek(command, executionContext)?.let { offer ->

            return pendingMachine.saveIntentDisambiguationPending(command, offer, service)

        }

        CommandRouteResolver.resolve(command, apiKey, llmClient, presets, appContext = executionContext)?.let { route ->

            route.clarifyMessage?.let { clarify ->

                return pendingMachine.saveRouteClarifyPending(command, clarify, route.steps, service)

            }

            if (IntentCapabilityMatrix.shouldExecuteRouteLocally(command, route)) {

                if (LocalFastPathGuard.needsPreview(route)) {

                    return pendingMachine.saveLocalPreviewPending(

                        command = command,

                        previewMessage = LocalFastPathGuard.previewMessage(route),

                        steps = route.steps,

                        service = service,

                    )

                }

                val routeResult = executeLocalSteps(executionContext, service, route.steps, command, runContext)

                if (routeResult.success && !routeResult.waitingForUserConfirm &&

                    LocalFastPathGuard.isUndoable(route.steps)

                ) {

                    LocalUndoRegistry.register(route.steps)

                }

                if (routeResult.success || routeResult.waitingForUserConfirm) {

                    return routeResult

                }

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

        service: AccessibilityGateway,

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

        pendingMachine.dropMemoryOnly()

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



    private suspend fun executeGuardedAction(
        context: Context,
        service: AccessibilityGateway?,
        session: AgentConversationSession,
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
    ): AgentGuardedActionExecutor.Outcome =
        AgentGuardedActionExecutor.execute(context, service, session, action, snapshot)



    private suspend fun runAgentLoop(

        loopCommand: String,

        rootCommand: String,

        apiKey: String,

        service: AccessibilityGateway,

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

        val memories = memoryStore.loadRecentMemories()

        val memoryPrompt = memoryStore.formatMemoriesForPrompt(
            memories,
            currentCommand = extractRootCommand(rootCommand),
        )

        var previousSnapshot: StructuredPageSnapshot? = initialSnapshot

        var stepNo = session.stepRecords.size
        val loopState = AgentLoopState()
        val pageObserver = AgentPageObservationCapture(appHintStore, visionDebugStore).apply {
            seedPreviousSnapshot(initialSnapshot)
        }

        val pageContextNeed = IntentCapabilityMatrix.inferPageContextNeed(loopCommand)

        if (pageContextNeed == IntentCapabilityMatrix.PageContextNeed.UI_FULL &&

            !contextConsentStore.hasConsented()

        ) {

            return AgentRunResult(

                success = false,

                summary = ContextConsentStore.SETTINGS_HINT,

                logs = emptyList(),

            )

        }

        val agentToolsPrompt = IntentCapabilityMatrix.toolsPromptForContext(pageContextNeed)

        suspend fun captureObservation(phase: String = "规划前"): PageObservationPayload {
            val observation = pageObserver.capture(
                service = service,
                session = session,
                stepNo = stepNo,
                pageContextNeed = pageContextNeed,
                phase = phase,
            )
            previousSnapshot = pageObserver.previousSnapshot
            return observation
        }

        var lastLlmScreenshotBase64: String? = null

        fun rememberLlmScreenshot(observation: PageObservationPayload, phase: String) {
            val shot = observation.screenshotBase64?.takeIf { it.isNotBlank() } ?: return
            lastLlmScreenshotBase64 = shot
            pageObserver.rememberLlmScreenshot(stepNo, phase, observation)
        }

        suspend fun continuePlanning(

            stepFeedback: String,

        ): JSONObject {

            val observation = captureObservation(phase = "规划续步")

            rememberLlmScreenshot(observation, phase = "规划续步")

            val loopContext = AgentLoopState.formatPlannerContext(
                state = loopState,
                session = session,
                stepNo = stepNo,
                maxSteps = MAX_AGENT_STEPS,
            )

            return llmClient.continueAfterStep(

                apiKey = apiKey,

                conversation = session,

                stepFeedback = stepFeedback,

                pageContext = observation.pageContext,

                pageDiff = observation.pageDiff,

                keyMemories = memoryPrompt,

                minimalPageContext = observation.minimalPageContext,

                pageContextMode = observation.mode,

                loopContext = loopContext,

                screenshotBase64 = observation.screenshotBase64,

                visionMode = observation.plannerVisionMode(),

            )

        }



        try {

            var json = if (resumeAfterUserReply) {

                llmClient.ensureSystemSeeded(session, memoryPrompt)

                val observation = captureObservation(phase = "用户续跑")

                rememberLlmScreenshot(observation, phase = "用户续跑")

                runContext.awaitContinuation()

                val resumeLoopContext = AgentLoopState.formatPlannerContext(
                    state = loopState,
                    session = session,
                    stepNo = stepNo,
                    maxSteps = MAX_AGENT_STEPS,
                )

                llmClient.continueAfterStep(

                    apiKey = apiKey,

                    conversation = session,

                    stepFeedback = "【用户已回答，请继续任务】\n$loopCommand",

                    pageContext = observation.pageContext,

                    pageDiff = observation.pageDiff,

                    keyMemories = memoryPrompt,

                    minimalPageContext = observation.minimalPageContext,

                    pageContextMode = observation.mode,

                    loopContext = resumeLoopContext,

                    screenshotBase64 = observation.screenshotBase64,

                    visionMode = observation.plannerVisionMode(),

                )

            } else {

                val observation = captureObservation(phase = "任务开始")

                rememberLlmScreenshot(observation, phase = "任务开始")

                val effectiveCommand = if (loopCommand != rootCommand) loopCommand else session.rootCommand

                runContext.awaitContinuation()

                val loopContext = AgentLoopState.formatPlannerContext(
                    state = loopState,
                    session = session,
                    stepNo = stepNo,
                    maxSteps = MAX_AGENT_STEPS,
                )

                llmClient.beginTask(

                    apiKey = apiKey,

                    conversation = session,

                    userCommand = effectiveCommand,

                    pageContext = observation.pageContext,

                    pageDiff = observation.pageDiff,

                    keyMemories = memoryPrompt,

                    minimalPageContext = observation.minimalPageContext,

                    pageContextMode = observation.mode,

                    toolsPrompt = agentToolsPrompt,

                    loopContext = loopContext,

                    screenshotBase64 = observation.screenshotBase64,

                    visionMode = observation.plannerVisionMode(),

                )

            }



            val actionQueue = ArrayDeque<AgentAction>()
            var activePlaybook: AgentActionPlaybook.Match? = null



            repeat(MAX_AGENT_STEPS) {

                coroutineContext.ensureActive()

                runContext.awaitContinuation()



                if (actionQueue.isEmpty()) {
                    activePlaybook?.let { playbook ->
                        AgentActionPlaybook.drainNextSteps(
                            playbook = playbook,
                            stepRecords = session.stepRecords,
                        )?.let { planned ->
                            actionQueue.addAll(planned)
                        }
                    }
                }

                if (actionQueue.isEmpty()) {
                    val planned = AgentPlanParser.parsePlan(json)
                    val expanded = AgentActionPlaybook.expandPlannedActions(planned)
                    if (expanded.activePlaybook != null) {
                        activePlaybook = expanded.activePlaybook
                    }
                    actionQueue.addAll(expanded.steps)

                    if (actionQueue.isEmpty()) {
                        activePlaybook?.let { playbook ->
                            AgentActionPlaybook.drainNextSteps(
                                playbook = playbook,
                                stepRecords = session.stepRecords,
                            )?.let { planned ->
                                actionQueue.addAll(planned)
                            }
                        }
                    }

                    if (actionQueue.isEmpty()) return@repeat

                }



                stepNo++

                var action = actionQueue.removeFirst()



                AgentActionGuard.sensitiveConfirmOverride(session, action)?.let { override ->

                    action = override

                }

                val preActionSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots())
                    ?: previousSnapshot
                MediaPlaybackHeuristics.interceptStuckPlaybackAction(
                    session = session,
                    snapshot = preActionSnapshot,
                    rootCommand = session.rootCommand,
                    action = action,
                )?.let { finishInstead ->
                    action = finishInstead
                }

                val tapCoords = VisionTapAnnotator.parseNormalizedCoords(action.targetText)
                if (action.action.equals("tap", ignoreCase = true) ||
                    (action.action.equals("send", ignoreCase = true) && tapCoords != null)
                ) {
                    VisionDebugRecorder.recordTapPlan(
                        store = visionDebugStore,
                        stepNo = stepNo,
                        action = action,
                        screenshotBase64 = lastLlmScreenshotBase64,
                    )
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

                    ).also {
                        activePlaybook = null
                    }

                }



                runContext.updateProgress(

                    stepNo,

                    if (actionQueue.isNotEmpty()) "执行：${action.action}（续）" else "执行：${action.action}",

                )

                onProgress?.invoke(stepNo, runContext.statusMessage)



                val hideOverlayForVision = VisionOverlayGuard.actionNeedsHiddenOverlay(
                    action = action,
                    snapshot = preActionSnapshot,
                )
                val outcome = if (hideOverlayForVision) {
                    VisionOverlayGuard.withHidden {
                        executeGuardedAction(service.context(), service, session, action, previousSnapshot)
                    }
                } else {
                    executeGuardedAction(service.context(), service, session, action, previousSnapshot)
                }
                when (outcome) {

                    is AgentGuardedActionExecutor.Outcome.Blocked -> {

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

                    is AgentGuardedActionExecutor.Outcome.NeedsConfirm -> {

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

                    is AgentGuardedActionExecutor.Outcome.Executed -> {

                        val result = outcome.result

                        postActionSettleDelay(service, action, result)

                        if (needsNavigationDelay(action)) {

                            delay(navigationDelayMs(action))

                        } else if (result.success) {

                            delay(ACTION_DELAY_MS)

                        }

                        coroutineContext.ensureActive()
                        runContext.awaitContinuation()

                        val beforeSnapshot = previousSnapshot
                        val observation = captureObservation(phase = "执行后")
                        val afterSnapshot = previousSnapshot
                        val verification = AgentVerifier.verify(
                            action = action,
                            executionResult = result,
                            beforeSnapshot = beforeSnapshot,
                            afterSnapshot = afterSnapshot,
                            pageDiff = observation.pageDiff,
                        )
                        val effectiveResult = if (
                            result.success &&
                            verification.status == AgentVerificationStatus.FAILED
                        ) {
                            ActionExecutionResult(
                                success = false,
                                summary = "验证未通过",
                                detail = verification.message,
                            )
                        } else {
                            result
                        }
                        loopState.afterStep(action, observation.pageDiff)

                        logs += AgentStepLog(

                            step = stepNo,

                            action = action,

                            success = effectiveResult.success,

                            detail = buildString {
                                append("[Agent] ${effectiveResult.toAgentFeedback()}")
                                verification.toFeedbackLine()?.let { line ->
                                    append(" | ").append(line)
                                }
                            },

                        )

                        session.recordStep(stepNo, action, effectiveResult, observation.pageDiff)

                        AppHintLearner.maybeLearn(

                            store = appHintStore,

                            packageName = beforeSnapshot?.packageName.orEmpty(),

                            action = action,

                            success = effectiveResult.success,

                        )



                        val feedback = buildString {

                            appendLine("【上一步执行结果】")

                            appendLine(

                                "操作：${action.action}" +

                                    action.targetText?.let { " target=\"$it\"" }.orEmpty() +

                                    action.inputText?.let { " input=\"$it\"" }.orEmpty(),

                            )

                            append(effectiveResult.toAgentFeedback())
                            verification.toFeedbackLine()?.let { line ->
                                appendLine()
                                append(line)
                            }

                            AgentStepAdvisor.postStepHint(

                                session = session,

                                action = action,

                                result = effectiveResult,

                                rootCommand = session.rootCommand,

                                snapshot = beforeSnapshot,

                                pageDiff = observation.pageDiff,

                                visionMode = observation.plannerVisionMode(),

                            )?.let { hint ->

                                appendLine()

                                append(hint)

                            }

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

                pendingMachine.restoreAfterFailedResume(

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

                pendingMachine.restoreAfterFailedResume(

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

                pendingMachine.restoreAfterFailedResume(

                    resumePending,

                    session,

                    previousSnapshot,

                )

            }

            return failResult

        } finally {
            com.tetraploid.joyforold.overlay.VisionOverlaySuppressors.current.deactivateVisionAgentMode()
        }

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

            pendingMachine.saveUserConfirmPending(
                originalCommand = userCommand,
                aiPrompt = rawSummary,
                session = session,
                previousSnapshot = previousSnapshot,
                needsBinaryConfirm = action.needsBinaryConfirm,
            )

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

        val store = memoryStore

        val extracted = llmClient.extractKeyMemory(apiKey, session.buildSessionSummary())

        store.saveFromSession(session, extracted)

    }



    private suspend fun runSystemIntentOnly(

        command: String,

        apiKey: String,

        context: Context,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

        val presets = presetStore.loadPresets()

        CommandRouteResolver.resolve(command, apiKey, llmClient, presets, appContext = context)?.let { route ->

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

        service: AccessibilityGateway?,

        steps: List<AgentAction>,

        userCommand: String,

        runContext: AgentRunContext,

    ): AgentRunResult {

        val logs = mutableListOf<AgentStepLog>()

        var stepNo = 0

        val memoryPrompt = memoryStore.formatMemoriesForPrompt(
            memoryStore.loadRecentMemories(),
            currentCommand = userCommand,
        )

        val localSession = AgentConversationSession(rootCommand = userCommand)

        var previousSnapshot = service?.mergeSnapshots(service.captureStructuredSnapshots())

        var lastInfoSummary: String? = null



        for (action in steps) {

            runContext.awaitContinuation()

            if (action.action.equals("finish", ignoreCase = true) || action.finished) {

                val rawSummary = lastInfoSummary ?: action.message ?: "任务已完成"

                val shouldWait = action.waitingForUser

                if (shouldWait) {

                    llmClient.ensureSystemSeeded(localSession, memoryPrompt)

                    localSession.addUser("【用户指令】$userCommand")

                    localSession.appendLocalStepsSummary(logs)

                    pendingMachine.saveUserConfirmPending(
                        originalCommand = userCommand,
                        aiPrompt = rawSummary,
                        session = localSession,
                        previousSnapshot = previousSnapshot,
                        needsBinaryConfirm = action.needsBinaryConfirm,
                    )

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

                is AgentGuardedActionExecutor.Outcome.NeedsConfirm -> {

                    val confirmAction = outcome.action

                    val rawSummary = confirmAction.message ?: "请确认是否继续"

                    llmClient.ensureSystemSeeded(localSession, memoryPrompt)

                    localSession.addUser("【用户指令】$userCommand")

                    localSession.appendLocalStepsSummary(logs)

                    pendingMachine.saveUserConfirmPending(
                        originalCommand = userCommand,
                        aiPrompt = rawSummary,
                        session = localSession,
                        previousSnapshot = previousSnapshot,
                        needsBinaryConfirm = confirmAction.needsBinaryConfirm,
                    )

                    return AgentRunResult(

                        success = true,

                        summary = rawSummary,

                        logs = logs,

                        waitingForUserConfirm = true,

                        confirmPrompt = rawSummary,

                        needsBinaryConfirm = confirmAction.needsBinaryConfirm,

                    )

                }

                is AgentGuardedActionExecutor.Outcome.Blocked -> {

                    logs += AgentStepLog(

                        step = stepNo,

                        action = action,

                        success = false,

                        detail = "[本地] ${outcome.reason}",

                    )

                    return AgentRunResult(false, outcome.reason, logs)

                }

                is AgentGuardedActionExecutor.Outcome.Executed -> {

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



    private suspend fun postActionSettleDelay(
        service: AccessibilityGateway,
        action: AgentAction,
        result: ActionExecutionResult,
    ) {
        when {
            action.action.equals("open_app", ignoreCase = true) && result.success -> {
                val expectedPackage = InstalledAppResolver.resolvePackage(
                    service.context(),
                    action.targetText.orEmpty(),
                )
                awaitReadablePage(service, expectedPackage = expectedPackage)
            }
            action.action.equals("wait", ignoreCase = true) ->
                delay(WAIT_ACTION_MS)
            action.action.equals("tap", ignoreCase = true) && result.success -> {
                val snap = service.captureBestStructuredSnapshot()
                if (PageReadiness.needsVisionFallback(snap)) {
                    delay(VISION_TAP_BEFORE_TYPE_MS)
                }
            }
            action.action.equals("type", ignoreCase = true) && result.success &&
                VisionTapAnnotator.parseNormalizedCoords(action.targetText) != null -> {
                delay(VISION_TAP_BEFORE_TYPE_MS)
            }
        }
    }

    private suspend fun awaitReadablePage(
        service: AccessibilityGateway,
        expectedPackage: String? = null,
        timeoutMs: Long = PAGE_READY_TIMEOUT_MS,
        pollMs: Long = PAGE_READY_POLL_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val merged = service.captureBestStructuredSnapshot()
            if (PageReadiness.isReadable(merged, expectedPackage = expectedPackage)) return true
            delay(pollMs)
        }
        return false
    }

    private fun needsNavigationDelay(action: AgentAction): Boolean {

        return action.action.equals("click", ignoreCase = true) ||

            action.action.equals("back", ignoreCase = true) ||

            action.action.equals("swipe_down", ignoreCase = true) ||

            action.action.equals("open_app", ignoreCase = true)

    }

    private fun navigationDelayMs(action: AgentAction): Long =
        if (action.action.equals("open_app", ignoreCase = true)) OPEN_APP_DELAY_MS else NAVIGATION_DELAY_MS

    suspend fun runDisambiguatedIntent(

        command: String,

        intentId: String,

        apiKey: String,

        appContext: Context,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)? = null,

    ): AgentRunResult {

        val service = AccessibilityGateways.current

            ?: return AgentRunResult(false, "无障碍服务未连接", emptyList())

        val steps = IntentDisambiguationHelper.stepsForIntent(command, intentId, appContext)

            ?: return AgentRunResult(false, "无法执行所选意图", emptyList())

        pendingMachine.clear()

        val result = executeLocalSteps(appContext, service, steps, command, runContext)

        if (result.success && !result.waitingForUserConfirm && LocalFastPathGuard.isUndoable(steps)) {

            LocalUndoRegistry.register(steps)

        }

        return result

    }



    companion object {

        private const val MAX_AGENT_STEPS = 30

        private const val ACTION_DELAY_MS = 100L
        private const val VISION_TAP_BEFORE_TYPE_MS = 800L

        private const val NAVIGATION_DELAY_MS = 280L

        private const val OPEN_APP_DELAY_MS = 500L

        private const val WAIT_ACTION_MS = 900L

        private const val PAGE_READY_TIMEOUT_MS = 6_000L

        private const val PAGE_READY_POLL_MS = 250L

        private val INFO_QUERY_ACTIONS = setOf(
            "tell_time",
            "query_weather",
            "read_unread_messages",
        )

    }

}


