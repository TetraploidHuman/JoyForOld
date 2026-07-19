package com.tetraploid.joyforold.agent



import android.content.Context

import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.accessibility.AccessibilityGateways
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_ASK_LLM
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_CAPTURE_PAGE_TEXTS
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetAskPolicy
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetDrain
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.overlay.VisionOverlayGuard
import com.tetraploid.joyforold.preset.PresetCommandStore
import com.tetraploid.joyforold.privacy.PageContextRedactor
import com.tetraploid.joyforold.privacy.SafeLog
import com.tetraploid.joyforold.system.AmapPoiResolver
import com.tetraploid.joyforold.system.SystemIntentExecutor
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

    suspend fun planUserFacingPhases(apiKey: String, userCommand: String): List<TaskPhaseItem> =
        llmClient.planUserFacingPhases(apiKey, userCommand)

    private val pendingExecutor = object : PendingExecutor {
        override suspend fun resumeUserConfirm(
            pending: PendingAgentState,
            command: String,
            apiKey: String,
            service: AccessibilityGateway,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult =
            // 禁止与 override 同名再调 this@…，Kotlin 会递归进本 override → StackOverflow
            continueAfterUserConfirm(pending, command, apiKey, service, runContext, onProgress)

        override suspend fun executeLocalSteps(
            context: Context,
            service: AccessibilityGateway,
            steps: List<AgentAction>,
            originalCommand: String,
            runContext: AgentRunContext,
        ): AgentRunResult {
            val result = runLocalSteps(
                context, service, steps, originalCommand, runContext,
            )
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

        override suspend fun runNavPoiPick(
            poiIntentId: String,
            originalCommand: String,
            appContext: Context,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = this@AgentOrchestrator.runNavPoiPick(
            poiIntentId, originalCommand, appContext, runContext, onProgress,
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

                val routeResult = runLocalSteps(executionContext, service, route.steps, command, runContext)

                if (routeResult.success && !routeResult.waitingForUserConfirm &&

                    LocalFastPathGuard.isUndoable(route.steps)

                ) {

                    LocalUndoRegistry.register(route.steps)

                }

                if (routeResult.success || routeResult.waitingForUserConfirm) {

                    return routeResult

                }

            } else {

                val seedActionSet = route.steps.firstOrNull { AgentActionSet.isRunActionSetAction(it) }

                if (seedActionSet != null && AgentActionSet.fromRunActionSetAction(seedActionSet) != null) {

                    return runAgentLoop(

                        command,

                        command,

                        apiKey,

                        service,

                        runContext,

                        onProgress,

                        seedActions = route.steps,

                    )

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



    private suspend fun continueAfterUserConfirm(

        pending: PendingAgentState,

        command: String,

        apiKey: String,

        service: AccessibilityGateway,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

        val userReply = command.trim()

        pending.session.recordConfirmAnswer(pending.aiPrompt, userReply)

        pendingMachine.dropMemoryOnly()

        // 发送确认通过后本地直接 send，避免 LLM 返回 send+finished 空跑「任务已完成」
        val confirmedSend = pending.needsBinaryConfirm &&
            AgentActionGuard.isSendConfirmPrompt(pending.aiPrompt) &&
            VoiceConfirmPhraseMatcher.classify(userReply) == VoiceConfirmPhraseMatcher.Intent.CONFIRM
        if (confirmedSend) {
            return runAgentLoop(
                loopCommand = pending.originalCommand,
                rootCommand = pending.session.rootCommand,
                apiKey = apiKey,
                service = service,
                runContext = runContext,
                onProgress = onProgress,
                existingSession = pending.session,
                initialSnapshot = pending.previousSnapshot,
                resumeAfterUserReply = false,
                resumePending = pending,
                seedActions = listOf(
                    AgentAction(action = "send"),
                    AgentAction(
                        action = "finish",
                        message = "消息已发送",
                        finished = true,
                    ),
                ),
            )
        }

        val enriched = ConfirmResumeBuilder.buildEnrichedResume(
            originalCommand = pending.originalCommand,
            aiPrompt = pending.aiPrompt,
            userReply = userReply,
        )

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
        observationStore: AgentObservationStore? = null,
    ): AgentGuardedActionExecutor.Outcome =
        AgentGuardedActionExecutor.execute(
            context = context,
            service = service,
            session = session,
            action = action,
            snapshot = snapshot,
            observationStore = observationStore,
        )



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

        seedActions: List<AgentAction>? = null,

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
        val observationStore = AgentObservationStore()

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

        suspend fun captureObservation(
            phase: String = "规划前",
            updatePlannerBaseline: Boolean = true,
        ): PageObservationPayload {
            val observation = pageObserver.capture(
                service = service,
                session = session,
                stepNo = stepNo,
                pageContextNeed = pageContextNeed,
                phase = phase,
                updatePlannerBaseline = updatePlannerBaseline,
            )
            previousSnapshot = pageObserver.previousSnapshot
            if (updatePlannerBaseline && pageContextNeed != IntentCapabilityMatrix.PageContextNeed.NONE) {
                pageObserver.lastCapturedSnapshot?.let { snap ->
                    observationStore.record(
                        step = stepNo,
                        snapshot = snap,
                        diff = observation.pageDiff,
                    )
                }
            }
            return observation
        }

        fun plannerLoopContext(): String = buildString {
            append(
                AgentLoopState.formatPlannerContext(
                    state = loopState,
                    session = session,
                    stepNo = stepNo,
                    maxSteps = MAX_AGENT_STEPS,
                ),
            )
            observationStore.formatPromptHint().takeIf { it.isNotBlank() }?.let { hint ->
                if (isNotEmpty()) appendLine()
                append(hint)
            }
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

            val loopContext = plannerLoopContext()

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

                val resumeLoopContext = plannerLoopContext()

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

            } else if (seedActions != null) {

                // 路由已给出动作组：跳过首轮主规划，直接 drain（省一次易误判 finish 的 LLM）

                llmClient.ensureSystemSeeded(session, memoryPrompt, toolsPrompt = agentToolsPrompt)

                captureObservation(phase = "动作组启动")

                org.json.JSONObject()

            } else {

                val observation = captureObservation(phase = "任务开始")

                rememberLlmScreenshot(observation, phase = "任务开始")

                val effectiveCommand = if (loopCommand != rootCommand) loopCommand else session.rootCommand

                runContext.awaitContinuation()

                val loopContext = plannerLoopContext()

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

            var activeActionSet: AgentActionSet.Match? = null

            var consumeSeedPlan = seedActions != null && !resumeAfterUserReply



            repeat(MAX_AGENT_STEPS) {

                coroutineContext.ensureActive()

                runContext.awaitContinuation()



                if (actionQueue.isEmpty()) {
                    activeActionSet?.let { actionSet ->
                        when (
                            processActionSetDrain(
                                actionSet = actionSet,
                                session = session,
                                service = service,
                                apiKey = apiKey,
                                actionQueue = actionQueue,
                                stepNoHolder = { stepNo },
                                setStepNo = { stepNo = it },
                            )
                        ) {
                            ActionSetDrainOutcome.SIDE_EFFECT -> return@repeat
                            ActionSetDrainOutcome.DONE -> activeActionSet = null
                            ActionSetDrainOutcome.QUEUED -> Unit
                        }
                    }
                }

                if (actionQueue.isEmpty()) {
                    if (consumeSeedPlan) {
                        consumeSeedPlan = false
                        val expanded = AgentActionSet.expandPlannedActions(seedActions.orEmpty())
                        if (expanded.activeActionSet != null && activeActionSet == null) {
                            activeActionSet = expanded.activeActionSet
                        }
                        actionQueue.addAll(expanded.steps)
                        if (actionQueue.isEmpty()) {
                            activeActionSet?.let { actionSet ->
                                when (
                                    processActionSetDrain(
                                        actionSet = actionSet,
                                        session = session,
                                        service = service,
                                        apiKey = apiKey,
                                        actionQueue = actionQueue,
                                        stepNoHolder = { stepNo },
                                        setStepNo = { stepNo = it },
                                    )
                                ) {
                                    ActionSetDrainOutcome.SIDE_EFFECT -> return@repeat
                                    ActionSetDrainOutcome.DONE -> activeActionSet = null
                                    ActionSetDrainOutcome.QUEUED -> Unit
                                }
                            }
                        }
                    } else {
                        val planned = AgentPlanParser.parsePlan(json)
                        val expanded = AgentActionSet.expandPlannedActions(planned)
                        if (expanded.activeActionSet != null) {
                            // 勿用新 Match 覆盖已在跑的动作组（会丢掉 askLlm 写回的 params）
                            if (activeActionSet == null) {
                                activeActionSet = expanded.activeActionSet
                            }
                        }
                        actionQueue.addAll(expanded.steps)

                        if (actionQueue.isEmpty()) {
                            activeActionSet?.let { actionSet ->
                                when (
                                    processActionSetDrain(
                                        actionSet = actionSet,
                                        session = session,
                                        service = service,
                                        apiKey = apiKey,
                                        actionQueue = actionQueue,
                                        stepNoHolder = { stepNo },
                                        setStepNo = { stepNo = it },
                                    )
                                ) {
                                    ActionSetDrainOutcome.SIDE_EFFECT -> return@repeat
                                    ActionSetDrainOutcome.DONE -> activeActionSet = null
                                    ActionSetDrainOutcome.QUEUED -> Unit
                                }
                            }
                        }
                    }

                    // 无事可做：交给 continuePlanning 会在下方「未取到动作」路径处理；
                    // 禁止空转 return@repeat 烧完 MAX_AGENT_STEPS。
                    if (actionQueue.isEmpty()) {
                        if (activeActionSet != null) {
                            // 动作组 drain 已 Done 却未入队：结束动作组，向模型要下一步
                            activeActionSet = null
                        }
                        // 落入下方会因 queue 空而无法执行；改为显式向 LLM 续跑
                        coroutineContext.ensureActive()
                        runContext.awaitContinuation()
                        json = continuePlanning(
                            "【系统】动作队列为空（动作组已结束或本轮无可执行步骤），请根据页面继续规划或 finish。",
                        )
                        return@repeat
                    }

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

                // 仅 action=finish 才终止；send/click 误带 finished:true 时须先真正执行（见 AgentAction.normalize）
                if (action.action.equals("finish", ignoreCase = true)) {

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
                        pageObserver.requestFullContext()

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
                        activeActionSet = null
                    }

                }



                runContext.updateProgress(

                    stepNo,

                    run {
                        val name = AgentActionSet.uiLabel(action) ?: action.action
                        if (actionQueue.isNotEmpty()) "执行：$name（续）" else "执行：$name"
                    },

                )

                onProgress?.invoke(stepNo, runContext.statusMessage)



                // click/手势必须藏悬浮窗：底栏「导航」会被交互卡片挡住（见 UITreeLog 窗口2）
                val hideOverlayForTouch = VisionOverlayGuard.actionNeedsHiddenOverlay(
                    action = action,
                    snapshot = preActionSnapshot,
                )
                val outcome = if (hideOverlayForTouch) {
                    VisionOverlayGuard.withHidden {
                        executeGuardedAction(service.context(), service, session, action, previousSnapshot, observationStore)
                    }
                } else {
                    executeGuardedAction(service.context(), service, session, action, previousSnapshot, observationStore)
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
                        pageObserver.requestFullContext()

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
                        val observation = captureObservation(
                            phase = "执行后",
                            updatePlannerBaseline = false,
                        )
                        val afterSnapshot = pageObserver.lastCapturedSnapshot ?: previousSnapshot
                        val verification = AgentVerifier.verify(
                            action = action,
                            executionResult = result,
                            beforeSnapshot = beforeSnapshot,
                            afterSnapshot = afterSnapshot,
                            pageDiff = observation.pageDiff,
                        )
                        // 动作组内：以无障碍执行结果为准。验证失败若再标 fail，
                        // 会触发 FlowEngine 重入队同一步 →「click（续）」死循环。
                        val effectiveResult = if (
                            activeActionSet == null &&
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



                        if (!effectiveResult.success) {
                            actionQueue.clear()
                            val actionSet = activeActionSet
                            if (actionSet != null) {
                                // 探测失败等应由动作组 onFail 分支消化，禁止直接拆掉 ActionSet
                                when (
                                    processActionSetDrain(
                                        actionSet = actionSet,
                                        session = session,
                                        service = service,
                                        apiKey = apiKey,
                                        actionQueue = actionQueue,
                                        stepNoHolder = { stepNo },
                                        setStepNo = { stepNo = it },
                                    )
                                ) {
                                    ActionSetDrainOutcome.QUEUED,
                                    ActionSetDrainOutcome.SIDE_EFFECT,
                                    -> return@repeat
                                    ActionSetDrainOutcome.DONE -> {
                                        // 动作组已尽力：轻量续规划，禁止立刻强制 FULL 重拉整树
                                        activeActionSet = null
                                    }
                                }
                            } else {
                                pageObserver.requestFullContext()
                            }
                        } else if (actionQueue.isNotEmpty()) {
                            // 本地队列（含 ActionSet 动作组）还有后续步骤：不调用 LLM
                            return@repeat
                        } else {
                            val actionSet = activeActionSet
                            if (actionSet != null) {
                                when (
                                    processActionSetDrain(
                                        actionSet = actionSet,
                                        session = session,
                                        service = service,
                                        apiKey = apiKey,
                                        actionQueue = actionQueue,
                                        stepNoHolder = { stepNo },
                                        setStepNo = { stepNo = it },
                                    )
                                ) {
                                    ActionSetDrainOutcome.QUEUED,
                                    ActionSetDrainOutcome.SIDE_EFFECT,
                                    -> return@repeat
                                    ActionSetDrainOutcome.DONE -> activeActionSet = null
                                }
                            } else {
                                activeActionSet = null
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

            // 确认态须展示悬浮确认卡：先退出视觉藏卡模式
            com.tetraploid.joyforold.overlay.VisionOverlaySuppressors.current.deactivateVisionAgentMode()

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

        SafeLog.i(
            "LLM usage[sessionEnd] session=${session.sessionId.take(8)} " +
                "prompt=${session.promptTokensTotal} completion=${session.completionTokensTotal} " +
                "total=${session.totalTokensTotal} steps=${session.stepRecords.size}",
        )

        return AgentRunResult(true, rawSummary, updatedLogs, sessionId = session.sessionId)

    }



    private suspend fun persistMemoryIfWorthy(apiKey: String, session: AgentConversationSession) {

        if (session.status != "success" && session.status != "done") return

        val store = memoryStore

        val extracted = llmClient.extractKeyMemory(apiKey, session.buildSessionSummary())

        store.saveFromSession(session, extracted)

    }

    private enum class ActionSetDrainOutcome {
        QUEUED,
        SIDE_EFFECT,
        DONE,
    }

    /**
     * 处理 ActionSet drain：入队普通动作；capture / askLlm 在本函数内链式做完并写伪步骤，
     * 直到得到 UI 动作批或 Done（避免副作用每轮烧一步又落回 parsePlan）。
     */
    private suspend fun processActionSetDrain(
        actionSet: AgentActionSet.Match,
        session: AgentConversationSession,
        service: AccessibilityGateway,
        apiKey: String,
        actionQueue: ArrayDeque<AgentAction>,
        stepNoHolder: () -> Int,
        setStepNo: (Int) -> Unit,
    ): ActionSetDrainOutcome {
        var sawSideEffect = false
        repeat(8) {
            when (val drain = AgentActionSet.drainNextSteps(actionSet, session.stepRecords)) {
                is ActionSetDrain.RunActions -> {
                    if (drain.steps.isEmpty()) return ActionSetDrainOutcome.DONE
                    actionQueue.addAll(drain.steps)
                    return ActionSetDrainOutcome.QUEUED
                }
                is ActionSetDrain.CapturePageTexts -> {
                    sawSideEffect = true
                    val next = stepNoHolder() + 1
                    setStepNo(next)
                    val snap = service.captureBestStructuredSnapshot()
                    // ActionSet 窄域候选：可点文案优先（列表项多半可点），不够再用可见文字。
                    // 绝不回传整棵 UI 树。单条上限放宽，避免淘宝商品长 desc 被裁掉。
                    val texts = buildList {
                        snap?.clickables?.let { addAll(it) }
                        if (isEmpty()) snap?.visibleTexts?.let { addAll(it) }
                    }
                        .map { it.trim() }
                        .filter { it.length in 2..400 }
                        .distinct()
                        .take(60)
                    val joined = texts.joinToString("|")
                    actionSet.updateParams(mapOf(drain.intoParam to joined))
                    session.recordStep(
                        step = next,
                        action = AgentAction(
                            action = ACTION_CAPTURE_PAGE_TEXTS,
                            targetText = drain.intoParam,
                            message = joined.take(200),
                        ),
                        result = ActionExecutionResult(true, "candidates=${texts.size}"),
                        pageDiff = "",
                    )
                }
                is ActionSetDrain.AskLlm -> {
                    sawSideEffect = true
                    val next = stepNoHolder() + 1
                    setStepNo(next)
                    val priorAttempts = session.stepRecords.count {
                        it.action.action.equals(ACTION_ASK_LLM, ignoreCase = true) &&
                            it.action.targetText == drain.phaseId
                    }
                    val updates = try {
                        llmClient.resolveActionSetAsk(
                            apiKey = apiKey,
                            systemPrompt = drain.systemPrompt,
                            userPrompt = drain.userPrompt,
                            writeFields = drain.writeFields,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    actionSet.updateParams(updates)
                    val unresolved = ActionSetAskPolicy.unresolvedFields(
                        params = actionSet.params,
                        writeFields = drain.writeFields,
                    )
                    if (unresolved.isNotEmpty()) {
                        if (ActionSetAskPolicy.shouldRetry(priorAttempts)) {
                            session.recordStep(
                                step = next,
                                action = AgentAction(
                                    action = ACTION_ASK_LLM,
                                    targetText = drain.phaseId,
                                    message = "missing=${unresolved.joinToString(",")}",
                                ),
                                result = ActionExecutionResult(
                                    success = false,
                                    summary = "askLlm retry",
                                ),
                                pageDiff = "",
                            )
                            // FlowEngine 见失败伪步骤会再次 AskLlm
                            return ActionSetDrainOutcome.SIDE_EFFECT
                        }
                        // 重试耗尽：记成功以越过 ask 相位，入队 finish，禁止空目标硬点
                        session.recordStep(
                            step = next,
                            action = AgentAction(
                                action = ACTION_ASK_LLM,
                                targetText = drain.phaseId,
                                message = "aborted=${unresolved.joinToString(",")}",
                            ),
                            result = ActionExecutionResult(
                                success = true,
                                summary = "askLlm aborted",
                            ),
                            pageDiff = "",
                        )
                        actionQueue.clear()
                        actionQueue.add(
                            AgentAction(
                                action = "finish",
                                message = ActionSetAskPolicy.abortFinishMessage(unresolved),
                                finished = true,
                                waitingForUser = true,
                            ),
                        )
                        return ActionSetDrainOutcome.QUEUED
                    }
                    session.recordStep(
                        step = next,
                        action = AgentAction(
                            action = ACTION_ASK_LLM,
                            targetText = drain.phaseId,
                            message = updates.entries.joinToString(",") { "${it.key}=${it.value}" }
                                .take(200),
                        ),
                        result = ActionExecutionResult(
                            success = true,
                            summary = "askLlm ok",
                        ),
                        pageDiff = "",
                    )
                }
                ActionSetDrain.Done -> return ActionSetDrainOutcome.DONE
            }
        }
        // 副作用链异常过长：若刚做过副作用，让外层再进一轮；否则视为结束
        return if (sawSideEffect) ActionSetDrainOutcome.SIDE_EFFECT else ActionSetDrainOutcome.DONE
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

            return runLocalSteps(context, service = null, route.steps, command, runContext)

        }

        return AgentRunResult(

            false,

            "无障碍服务未连接，请回到应用稍候或重新打开应用后再试",

            emptyList(),

        )

    }



    private suspend fun runLocalSteps(

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

            if (action.action.equals("navigate_pick", ignoreCase = true)) {
                val query = action.targetText?.trim().orEmpty()
                val nearLandmark = action.inputText?.trim()?.ifBlank { null }
                stepNo++
                val pickOutcome = handleNavigatePick(
                    context = context,
                    service = service,
                    query = query,
                    nearLandmark = nearLandmark,
                    userCommand = userCommand,
                    stepNo = stepNo,
                    logs = logs,
                )
                if (pickOutcome != null) return pickOutcome
                continue
            }

            if (action.action.equals("finish", ignoreCase = true)) {

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
        val name = action.action.lowercase()
        return name == "open_app" || name == "navigate_to" || name == "navigate_home"
    }

    private fun navigationDelayMs(action: AgentAction): Long =
        when {
            action.action.equals("open_app", ignoreCase = true) -> OPEN_APP_DELAY_MS
            action.action.equals("navigate_to", ignoreCase = true) -> OPEN_APP_DELAY_MS
            else -> NAVIGATION_DELAY_MS
        }

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
        val result = runLocalSteps(appContext, service, steps, command, runContext)
        if (result.success && !result.waitingForUserConfirm && LocalFastPathGuard.isUndoable(steps)) {
            LocalUndoRegistry.register(steps)
        }
        return result
    }

    suspend fun runNavPoiPick(
        poiIntentId: String,
        originalCommand: String,
        appContext: Context,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)? = null,
    ): AgentRunResult {
        val poi = NavPoiPickCodec.parse(poiIntentId)
            ?: return AgentRunResult(false, "无法识别所选地点", emptyList())
        pendingMachine.clear()
        onProgress?.invoke(1, "执行：导航前往${poi.name}")
        val exec = SystemIntentExecutor.navigateToPoi(appContext, poi)
        return AgentRunResult(
            success = exec.success,
            summary = if (exec.success) "正在为您导航前往：${poi.name}" else exec.summary,
            logs = listOf(
                AgentStepLog(
                    step = 1,
                    action = AgentAction(action = "navigate_to", targetText = poi.name),
                    success = exec.success,
                    detail = exec.summary,
                ),
            ),
        )
    }

    private fun handleNavigatePick(
        context: Context,
        service: AccessibilityGateway?,
        query: String,
        nearLandmark: String?,
        userCommand: String,
        stepNo: Int,
        logs: MutableList<AgentStepLog>,
    ): AgentRunResult? {
        if (query.isBlank()) {
            logs += AgentStepLog(
                step = stepNo,
                action = AgentAction(action = "navigate_pick"),
                success = false,
                detail = "未指定目的地",
            )
            return AgentRunResult(false, "未指定目的地", logs)
        }
        val displayQuery = when {
            nearLandmark.isNullOrBlank() -> query
            AmapPoiResolver.looksLikeAdminRegion(nearLandmark) -> "${nearLandmark}的$query"
            else -> "${nearLandmark}附近的$query"
        }
        val candidates = if (!nearLandmark.isNullOrBlank()) {
            AmapPoiResolver.searchNearLandmark(context, nearLandmark, query)
        } else {
            // 若 query 本身是「行政区的品类」，也要按区域搜，避免用桂阳 GPS
            val scoped = SystemIntentLocalParser.splitScopedPoiQuery(query)
            if (scoped != null) {
                AmapPoiResolver.searchNearLandmark(context, scoped.landmark, scoped.poi)
            } else {
                AmapPoiResolver.searchCandidates(context, query)
            }
        }
        when {
            candidates.isEmpty() -> {
                // 无 Web 结果时降级为 navigate_to（keywordNavi）
                val fallback = SystemIntentExecutor.execute(context, "navigate_to", query, nearLandmark)
                logs += AgentStepLog(
                    step = stepNo,
                    action = AgentAction(action = "navigate_to", targetText = query, inputText = nearLandmark),
                    success = fallback.success,
                    detail = fallback.summary,
                )
                return AgentRunResult(
                    success = fallback.success,
                    summary = if (fallback.success) "正在为您导航前往：$displayQuery" else fallback.summary,
                    logs = logs,
                )
            }
            candidates.size == 1 -> {
                val poi = candidates.first()
                val exec = SystemIntentExecutor.navigateToPoi(context, poi)
                logs += AgentStepLog(
                    step = stepNo,
                    action = AgentAction(action = "navigate_to", targetText = poi.name),
                    success = exec.success,
                    detail = exec.summary,
                )
                return AgentRunResult(
                    success = exec.success,
                    summary = if (exec.success) "正在为您导航前往：${poi.name}" else exec.summary,
                    logs = logs,
                )
            }
            else -> {
                val options = candidates.mapIndexed { index, poi ->
                    NavPoiPickCodec.toOption(poi, index)
                }
                logs += AgentStepLog(
                    step = stepNo,
                    action = AgentAction(action = "navigate_pick", targetText = query, inputText = nearLandmark),
                    success = true,
                    detail = "待用户从 ${options.size} 个候选中选择",
                )
                val wait = pendingMachine.saveNavPoiPickPending(
                    command = userCommand,
                    query = displayQuery,
                    options = options,
                    service = service,
                )
                return wait.copy(logs = logs)
            }
        }
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


