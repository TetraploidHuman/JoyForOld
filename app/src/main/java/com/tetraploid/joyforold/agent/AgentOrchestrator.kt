package com.tetraploid.joyforold.agent



import android.content.Context

import com.tetraploid.joyforold.accessibility.JoyAccessibilityService

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

    private val deepSeekClient: DeepSeekClient = DeepSeekClient(),

    private var memoryStore: AgentMemoryStore? = null,

    private var sessionStore: AgentSessionStore? = null,

    private var appHintStore: AppHintStore? = null,

    private var presetStore: PresetCommandStore? = null,

    private var visionDebugStore: VisionDebugStore? = null,

) {

    private var pendingState: PendingAgentState? = null

    private var contextConsentStore: ContextConsentStore? = null



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



    fun bindVisionDebugStore(store: VisionDebugStore) {

        visionDebugStore = store

    }



    fun bindContextConsentStore(store: ContextConsentStore) {

        contextConsentStore = store

    }



    fun restorePendingFromDisk() {

        if (pendingState != null) return

        pendingState = sessionStore?.loadPending()

    }



    fun peekPendingPrompt(): String? = pendingState?.aiPrompt



    fun peekPendingKind(): PendingKind = pendingState?.kind ?: PendingKind.USER_CONFIRM

    fun peekPendingOriginalCommand(): String? = pendingState?.originalCommand

    fun peekPendingNeedsBinaryConfirm(): Boolean = pendingState?.needsBinaryConfirm ?: false

    fun peekDisambiguationOptions(): List<DisambiguationOption> =
        decodeDisambiguationOptions(pendingState?.deferredCommand)



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

                    PendingKind.LOCAL_PREVIEW -> handleLocalPreviewReply(

                        pending, command, service, runContext,

                    )

                    PendingKind.INTENT_DISAMBIGUATION -> handleIntentDisambiguationReply(

                        pending, command, apiKey, service, runContext, onProgress,

                    )

                    PendingKind.CONTEXT_CONSENT -> {

                        clearPendingUserReply()

                        AgentRunResult(

                            false,

                            ContextConsentStore.SETTINGS_HINT,

                            emptyList(),

                        )

                    }

                }

            }

        }



        if (pendingState != null) {

            return promptTaskAbandon(command, service)

        }



        val presets = presetStore?.loadPresets().orEmpty()

        IntentDisambiguationHelper.peek(command, executionContext)?.let { offer ->

            return saveIntentDisambiguationPending(command, offer, service)

        }

        CommandRouteResolver.resolve(command, apiKey, deepSeekClient, presets, appContext = executionContext)?.let { route ->

            route.clarifyMessage?.let { clarify ->

                return saveRouteClarifyPending(command, clarify, route.steps, service)

            }

            if (IntentCapabilityMatrix.shouldExecuteRouteLocally(command, route)) {

                if (LocalFastPathGuard.needsPreview(route)) {

                    return saveLocalPreviewPending(

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



        val currentSnapshot = snapshot ?: service?.mergeSnapshots(service.captureStructuredSnapshots())

        AgentActionGuard.blockedRepeatReason(
            session,
            action,
            pageUnchangedSinceLastStep = session.stepRecords.lastOrNull()
                ?.pageDiff
                ?.let(AgentActionGuard::pageDiffIndicatesNoChange)
                ?: false,
            a11yUnavailable = PageReadiness.needsVisionFallback(currentSnapshot),
        )?.let { return GuardOutcome.Blocked(it) }

        PageReadiness.needsVisionFallback(currentSnapshot).takeIf { it }?.let {
            AgentActionGuard.blockedInVisionMode(action)?.let { reason ->
                return GuardOutcome.Blocked(reason)
            }
        }

        if (action.action.equals("open_app", ignoreCase = true) && service != null) {
            val targetPkg = InstalledAppResolver.resolvePackage(
                context,
                action.targetText.orEmpty(),
            )
            if (!targetPkg.isNullOrBlank() && currentSnapshot?.packageName == targetPkg) {
                return GuardOutcome.Blocked(
                    "目标应用已在当前前台（$targetPkg），请勿重复 open_app；请根据截图继续 tap/type。",
                )
            }
        }

        RiskScreenGuard.blockReason(currentSnapshot, action)?.let { return GuardOutcome.Blocked(it) }



        AgentToolRegistry.executeSystemIntent(context, action)?.let {
            return GuardOutcome.Executed(it)
        }

        if (service == null) {
            return GuardOutcome.Blocked("需要无障碍服务才能执行：${action.action}")
        }

        val result = AgentToolRegistry.execute(context, service, action)

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
        var previousVisionFingerprint: String? = null

        var stepNo = session.stepRecords.size
        val loopState = AgentLoopState()

        val pageContextNeed = IntentCapabilityMatrix.inferPageContextNeed(loopCommand)

        if (pageContextNeed == IntentCapabilityMatrix.PageContextNeed.UI_FULL &&

            contextConsentStore?.hasConsented() != true

        ) {

            return AgentRunResult(

                success = false,

                summary = ContextConsentStore.SETTINGS_HINT,

                logs = emptyList(),

            )

        }

        val agentToolsPrompt = IntentCapabilityMatrix.toolsPromptForContext(pageContextNeed)



        suspend fun captureObservation(phase: String = "规划前"): PageObservationPayload {

            suspend fun captureVisionScreenshot(): String? =
                VisionOverlayGuard.withHidden { service.captureScreenshotBase64() }

            if (pageContextNeed == IntentCapabilityMatrix.PageContextNeed.NONE) {

                return PageObservationPayload(

                    pageContext = "",

                    pageDiff = "",

                    minimalPageContext = "",

                    mode = PageContextMode.NONE,

                )

            }

            val merged = service.captureBestStructuredSnapshot()
            if (merged == null) {
                val screenshot = captureVisionScreenshot()
                val visionMode = !screenshot.isNullOrBlank()
                val currentVisionFp = VisionScreenChange.fingerprint(screenshot)
                val baseDiff = "无法读取页面"
                val pageDiff = if (visionMode) {
                    PageContextRedactor.redact(
                        VisionScreenChange.augmentPageDiff(
                            baseDiff,
                            previousVisionFingerprint,
                            currentVisionFp,
                        ),
                    )
                } else {
                    baseDiff
                }
                if (currentVisionFp != null) {
                    previousVisionFingerprint = currentVisionFp
                }
                AgentPageDebugLog.logObservation(
                    stepNo = stepNo,
                    phase = phase,
                    service = service,
                    snapshot = null,
                    pageDiff = pageDiff,
                    visionMode = visionMode,
                    a11yUnavailable = true,
                    screenshotChars = screenshot?.length ?: 0,
                )
                return PageObservationPayload(
                    pageContext = if (visionMode) {
                        "无法读取无障碍树，已附带屏幕截图供视觉识别。"
                    } else {
                        "无法读取页面，请切换到目标应用。"
                    },
                    pageDiff = pageDiff,
                    minimalPageContext = if (visionMode) "视觉观察" else "无法读取页面",
                    mode = PageContextMode.FULL,
                    screenshotBase64 = screenshot,
                    visionMode = visionMode,
                    a11yUnavailable = true,
                )
            }

            val enriched = enrichWithAppHints(merged)
            val readable = PageReadiness.isReadable(enriched)
            val visionFallback = PageReadiness.needsVisionFallback(enriched)
            val screenshot = if (visionFallback) captureVisionScreenshot() else null
            val visionOnlyApp = VisionOnlyApps.isVisionOnly(enriched.packageName)
            val visionMode = visionFallback && (
                !screenshot.isNullOrBlank() || visionOnlyApp
                )

            val currentVisionFp = VisionScreenChange.fingerprint(screenshot)

            val pageDiff = PageContextRedactor.redact(
                if (visionFallback) {
                    VisionPageContext.formatPageDiff(
                        packageName = enriched.packageName,
                        previousSnapshot = previousSnapshot,
                        previousVisionFingerprint = previousVisionFingerprint,
                        currentVisionFingerprint = currentVisionFp,
                    )
                } else {
                    PageObservation.diff(previousSnapshot, enriched)
                },
            )
            if (currentVisionFp != null) {
                previousVisionFingerprint = currentVisionFp
            }

            val dynamicMode = PageContextSelector.modeFor(previousSnapshot, enriched, pageDiff)

            previousSnapshot = enriched

            val mode = IntentCapabilityMatrix.pageContextModeForNeed(pageContextNeed, dynamicMode)

            AgentPageDebugLog.logObservation(
                stepNo = stepNo,
                phase = phase,
                service = service,
                snapshot = enriched,
                pageDiff = pageDiff,
                visionMode = visionMode,
                a11yUnavailable = visionFallback,
                screenshotChars = screenshot?.length ?: 0,
            )

            return PageObservationPayload(
                pageContext = PageContextRedactor.redact(
                    buildString {
                        if (visionFallback) {
                            append(
                                VisionPageContext.formatPageContext(
                                    enriched,
                                    hasScreenshot = !screenshot.isNullOrBlank(),
                                ),
                            )
                            appendLine()
                            appendLine(
                                "【系统提示】当前应用不提供可用无障碍 UI 信息；" +
                                    "请以截图（若有）识别界面，使用 tap/type/send，禁止 click/read_tree/find_on_page。",
                            )
                            VisionTaskHint.pageContextSupplement(
                                command = session.rootCommand,
                                steps = session.stepRecords,
                                visionMode = true,
                            ).takeIf { it.isNotBlank() }?.let { supplement ->
                                appendLine()
                                append(supplement)
                            }
                        } else {
                            append(enriched.toCompactSummary())
                            if (!readable) {
                                appendLine()
                                appendLine(
                                    "【系统提示】当前读到的是系统壳层或应用仍在启动，请 wait；" +
                                        "确认 pageContext 已是目标应用后再规划下一步。",
                                )
                            }
                            SearchTaskHeuristics.plannerSupplement(
                                command = session.rootCommand,
                                snapshot = enriched,
                            ).takeIf { it.isNotBlank() }?.let { supplement ->
                                appendLine()
                                append(supplement)
                            }
                        }
                    },
                ),
                pageDiff = pageDiff,
                minimalPageContext = PageContextRedactor.redact(
                    if (visionFallback) {
                        "${enriched.packageName.ifBlank { "未知应用" }} | 视觉观察（无无障碍 UI）"
                    } else if (readable) {
                        enriched.toMinimalSummary()
                    } else {
                        "${enriched.packageName.ifBlank { "未知应用" }} | 页面未就绪"
                    },
                ),
                mode = mode,
                screenshotBase64 = screenshot,
                visionMode = visionMode,
                a11yUnavailable = visionFallback,
            )

        }



        var lastLlmScreenshotBase64: String? = null

        fun rememberLlmScreenshot(observation: PageObservationPayload, phase: String) {
            val shot = observation.screenshotBase64?.takeIf { it.isNotBlank() } ?: return
            lastLlmScreenshotBase64 = shot
            VisionDebugRecorder.recordLlmInput(
                store = visionDebugStore,
                stepNo = stepNo,
                phase = phase,
                screenshotBase64 = shot,
            )
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

            return deepSeekClient.continueAfterStep(

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

                deepSeekClient.ensureSystemSeeded(session, memoryPrompt)

                val observation = captureObservation(phase = "用户续跑")

                rememberLlmScreenshot(observation, phase = "用户续跑")

                runContext.awaitContinuation()

                val resumeLoopContext = AgentLoopState.formatPlannerContext(
                    state = loopState,
                    session = session,
                    stepNo = stepNo,
                    maxSteps = MAX_AGENT_STEPS,
                )

                deepSeekClient.continueAfterStep(

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

                    loopContext = loopContext,

                    screenshotBase64 = observation.screenshotBase64,

                    visionMode = observation.plannerVisionMode(),

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

                    )

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
                        executeGuardedAction(service, service, session, action, previousSnapshot)
                    }
                } else {
                    executeGuardedAction(service, service, session, action, previousSnapshot)
                }
                when (outcome) {

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

                restorePendingAfterFailedResume(

                    resumePending,

                    session,

                    previousSnapshot,

                )

            }

            return maxStepResult

        } catch (_: CancellationException) {

            AgentRuntime.resetVisionOverlaySuppression()

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

            AgentRuntime.resetVisionOverlaySuppression()

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



    private suspend fun postActionSettleDelay(
        service: JoyAccessibilityService,
        action: AgentAction,
        result: ActionExecutionResult,
    ) {
        when {
            action.action.equals("open_app", ignoreCase = true) && result.success -> {
                val expectedPackage = InstalledAppResolver.resolvePackage(
                    service.applicationContext,
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
        }
    }

    private suspend fun awaitReadablePage(
        service: JoyAccessibilityService,
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



    private fun enrichWithAppHints(snapshot: StructuredPageSnapshot): StructuredPageSnapshot {
        val a11yReadable = PageReadiness.isReadable(snapshot)
        val stored = appHintStore?.formatForPrompt(snapshot.packageName, a11yReadable).orEmpty()
        if (stored.isBlank()) return snapshot
        val combined = listOf(snapshot.appHint, stored).filter { it.isNotBlank() }.joinToString("\n")
        return snapshot.copy(appHint = combined)
    }



    suspend fun runDisambiguatedIntent(

        command: String,

        intentId: String,

        apiKey: String,

        appContext: Context,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)? = null,

    ): AgentRunResult {

        val service = JoyAccessibilityService.instance

            ?: return AgentRunResult(false, "无障碍服务未连接", emptyList())

        val steps = IntentDisambiguationHelper.stepsForIntent(command, intentId, appContext)

            ?: return AgentRunResult(false, "无法执行所选意图", emptyList())

        pendingState = null

        sessionStore?.clearPending()

        val result = executeLocalSteps(appContext, service, steps, command, runContext)

        if (result.success && !result.waitingForUserConfirm && LocalFastPathGuard.isUndoable(steps)) {

            LocalUndoRegistry.register(steps)

        }

        return result

    }



    private fun saveIntentDisambiguationPending(

        command: String,

        offer: DisambiguationOffer,

        service: JoyAccessibilityService,

    ): AgentRunResult {

        val prompt = "您说的是「${offer.command}」吗？请点选或说出要执行的操作。"

        val state = PendingAgentState(

            originalCommand = command,

            aiPrompt = prompt,

            session = AgentConversationSession(rootCommand = command),

            previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),

            kind = PendingKind.INTENT_DISAMBIGUATION,

            deferredCommand = encodeDisambiguationOptions(offer.options),

        )

        savePendingState(state)

        return AgentRunResult(

            success = true,

            summary = prompt,

            logs = emptyList(),

            waitingForUserConfirm = true,

            confirmPrompt = prompt,

        )

    }



    private suspend fun handleIntentDisambiguationReply(

        pending: PendingAgentState,

        command: String,

        apiKey: String,

        service: JoyAccessibilityService,

        runContext: AgentRunContext,

        onProgress: ((Int, String) -> Unit)?,

    ): AgentRunResult {

        val options = decodeDisambiguationOptions(pending.deferredCommand)

        val matched = options.firstOrNull { option ->

            command.contains(option.label, ignoreCase = true) ||

                command.contains(option.intentId, ignoreCase = true)

        } ?: options.firstOrNull()

        if (matched == null) {

            return AgentRunResult(

                success = true,

                summary = pending.aiPrompt,

                logs = emptyList(),

                waitingForUserConfirm = true,

                confirmPrompt = pending.aiPrompt,

            )

        }

        return runDisambiguatedIntent(

            command = pending.originalCommand,

            intentId = matched.intentId,

            apiKey = apiKey,

            appContext = service,

            runContext = runContext,

            onProgress = onProgress,

        )

    }



    private fun saveLocalPreviewPending(

        command: String,

        previewMessage: String,

        steps: List<AgentAction>,

        service: JoyAccessibilityService,

    ): AgentRunResult {

        val state = PendingAgentState(

            originalCommand = command,

            aiPrompt = previewMessage,

            session = AgentConversationSession(rootCommand = command),

            previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),

            kind = PendingKind.LOCAL_PREVIEW,

            plannedSteps = steps,

        )

        savePendingState(state)

        return AgentRunResult(

            success = true,

            summary = previewMessage,

            logs = emptyList(),

            waitingForUserConfirm = true,

            confirmPrompt = previewMessage,

            needsBinaryConfirm = true,

        )

    }



    private suspend fun handleLocalPreviewReply(

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

                    AgentRunResult(false, "没有可执行的步骤", emptyList())

                } else {

                    val result = executeLocalSteps(service, service, steps, pending.originalCommand, runContext)

                    if (result.success && !result.waitingForUserConfirm && LocalFastPathGuard.isUndoable(steps)) {

                        LocalUndoRegistry.register(steps)

                    }

                    result

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

                    needsBinaryConfirm = true,

                )

            }

        }

    }



    private fun encodeDisambiguationOptions(options: List<DisambiguationOption>): String {

        val arr = org.json.JSONArray()

        options.forEach { option ->

            arr.put(

                org.json.JSONObject()

                    .put("intent", option.intentId)

                    .put("label", option.label),

            )

        }

        return arr.toString()

    }



    private fun decodeDisambiguationOptions(raw: String?): List<DisambiguationOption> {

        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {

            val arr = org.json.JSONArray(raw)

            buildList {

                for (i in 0 until arr.length()) {

                    val item = arr.getJSONObject(i)

                    add(

                        DisambiguationOption(

                            intentId = item.optString("intent"),

                            label = item.optString("label"),

                            confidence = 0f,

                        ),

                    )

                }

            }

        }.getOrDefault(emptyList())

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


