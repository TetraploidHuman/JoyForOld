package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待确认 / 待恢复任务的状态机：持久化、创建与回复分发。
 */
internal class PendingStateMachine(
    private var sessionStore: AgentSessionStore? = null,
) {
    private var pendingState: PendingAgentState? = null

    fun bindSessionStore(store: AgentSessionStore) {
        sessionStore = store
    }

    fun dropMemoryOnly() {
        pendingState = null
    }

    fun restoreFromDisk() {
        if (pendingState != null) return
        pendingState = sessionStore?.loadPending()
    }

    fun current(): PendingAgentState? = pendingState

    fun peekPendingPrompt(): String? = pendingState?.aiPrompt

    fun peekPendingKind(): PendingKind = pendingState?.kind ?: PendingKind.USER_CONFIRM

    fun peekPendingOriginalCommand(): String? = pendingState?.originalCommand

    fun peekPendingNeedsBinaryConfirm(): Boolean = pendingState?.needsBinaryConfirm ?: false

    fun peekDisambiguationOptions(): List<DisambiguationOption> =
        decodeDisambiguationOptions(pendingState?.deferredCommand)

    fun hasPending(): Boolean = pendingState != null

    fun isTaskAbandonKind(): Boolean = pendingState?.kind == PendingKind.TASK_ABANDON

    fun clear() {
        pendingState = null
        sessionStore?.clearPending()
    }

    fun save(state: PendingAgentState) {
        pendingState = state
        sessionStore?.savePending(state)
    }

    fun restoreAfterFailedResume(
        original: PendingAgentState,
        session: AgentConversationSession,
        previousSnapshot: StructuredPageSnapshot?,
    ) {
        save(
            original.copy(
                session = session,
                previousSnapshot = previousSnapshot ?: original.previousSnapshot,
            ),
        )
    }

    fun saveUserConfirmPending(
        originalCommand: String,
        aiPrompt: String,
        session: AgentConversationSession,
        previousSnapshot: StructuredPageSnapshot?,
        needsBinaryConfirm: Boolean,
    ) {
        save(
            PendingAgentState(
                originalCommand = originalCommand,
                aiPrompt = aiPrompt,
                session = session,
                previousSnapshot = previousSnapshot,
                kind = PendingKind.USER_CONFIRM,
                needsBinaryConfirm = needsBinaryConfirm,
            ),
        )
    }

    fun saveRouteClarifyPending(
        command: String,
        clarifyMessage: String,
        steps: List<AgentAction>,
        service: AccessibilityGateway,
    ): AgentRunResult {
        val state = PendingAgentState(
            originalCommand = command,
            aiPrompt = clarifyMessage,
            session = AgentConversationSession(rootCommand = command),
            previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),
            kind = PendingKind.ROUTE_CLARIFY,
            plannedSteps = steps,
        )
        save(state)
        return waitingResult(clarifyMessage, needsBinaryConfirm = false)
    }

    fun saveIntentDisambiguationPending(
        command: String,
        offer: DisambiguationOffer,
        service: AccessibilityGateway,
    ): AgentRunResult {
        val prompt = "您说的是「${offer.command}」吗？请点选或说出要执行的操作。"
        save(
            PendingAgentState(
                originalCommand = command,
                aiPrompt = prompt,
                session = AgentConversationSession(rootCommand = command),
                previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),
                kind = PendingKind.INTENT_DISAMBIGUATION,
                deferredCommand = encodeDisambiguationOptions(offer.options),
            ),
        )
        return waitingResult(prompt, needsBinaryConfirm = false)
    }

    fun saveLocalPreviewPending(
        command: String,
        previewMessage: String,
        steps: List<AgentAction>,
        service: AccessibilityGateway,
    ): AgentRunResult {
        save(
            PendingAgentState(
                originalCommand = command,
                aiPrompt = previewMessage,
                session = AgentConversationSession(rootCommand = command),
                previousSnapshot = service.mergeSnapshots(service.captureStructuredSnapshots()),
                kind = PendingKind.LOCAL_PREVIEW,
                plannedSteps = steps,
            ),
        )
        return waitingResult(previewMessage, needsBinaryConfirm = true)
    }

    fun promptTaskAbandon(
        newCommand: String,
        service: AccessibilityGateway,
    ): AgentRunResult {
        val existing = pendingState ?: return AgentRunResult(false, "没有待处理任务", emptyList())
        val prompt = "您有未完成的任务：${existing.aiPrompt.take(80)}。要放弃并开始新指令吗？请说「放弃」或「继续」。"
        save(
            PendingAgentState(
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
            ),
        )
        return waitingResult(prompt, needsBinaryConfirm = false)
    }

    suspend fun resumePending(
        pending: PendingAgentState,
        command: String,
        apiKey: String,
        service: AccessibilityGateway,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
        executor: PendingExecutor,
    ): AgentRunResult = when (pending.kind) {
        PendingKind.ROUTE_CLARIFY -> handleRouteClarifyReply(pending, command, service, executor, runContext)
        PendingKind.TASK_ABANDON -> handleTaskAbandonReply(command, apiKey, service, runContext, onProgress, executor)
        PendingKind.USER_CONFIRM -> executor.resumeUserConfirm(
            pending, command, apiKey, service, runContext, onProgress,
        )
        PendingKind.LOCAL_PREVIEW -> handleLocalPreviewReply(pending, command, service, executor, runContext)
        PendingKind.INTENT_DISAMBIGUATION -> handleIntentDisambiguationReply(
            pending, command, apiKey, service, runContext, onProgress, executor,
        )
        PendingKind.CONTEXT_CONSENT -> {
            clear()
            AgentRunResult(false, ContextConsentStore.SETTINGS_HINT, emptyList())
        }
    }

    suspend fun handleTaskAbandonReply(
        command: String,
        apiKey: String,
        service: AccessibilityGateway,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
        executor: PendingExecutor,
    ): AgentRunResult {
        val pending = pendingState ?: return AgentRunResult(false, "没有待处理任务", emptyList())
        return when (PendingAbandonPhraseMatcher.classify(command)) {
            PendingAbandonPhraseMatcher.Intent.ABANDON -> {
                val deferred = pending.deferredCommand?.trim().orEmpty()
                clear()
                if (deferred.isBlank()) {
                    AgentRunResult(true, "已放弃未完成任务", emptyList())
                } else {
                    executor.runNewCommand(deferred, apiKey, service, runContext, onProgress)
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
                save(restored)
                waitingResult(restored.aiPrompt, restored.needsBinaryConfirm)
            }
            PendingAbandonPhraseMatcher.Intent.UNCLEAR ->
                waitingResult(pending.aiPrompt, needsBinaryConfirm = false)
        }
    }

    private suspend fun handleRouteClarifyReply(
        pending: PendingAgentState,
        command: String,
        service: AccessibilityGateway,
        executor: PendingExecutor,
        runContext: AgentRunContext,
    ): AgentRunResult = when (VoiceConfirmPhraseMatcher.classify(command)) {
        VoiceConfirmPhraseMatcher.Intent.CONFIRM -> {
            val steps = pending.plannedSteps.orEmpty()
            clear()
            if (steps.isEmpty()) {
                AgentRunResult(false, "没有可执行的预设步骤", emptyList())
            } else {
                executor.executeLocalSteps(
                    service.context(),
                    service,
                    steps,
                    pending.originalCommand,
                    runContext,
                )
            }
        }
        VoiceConfirmPhraseMatcher.Intent.CANCEL -> {
            clear()
            AgentRunResult(true, "好的，已取消", emptyList())
        }
        VoiceConfirmPhraseMatcher.Intent.UNCLEAR ->
            waitingResult(pending.aiPrompt, needsBinaryConfirm = false)
    }

    private suspend fun handleLocalPreviewReply(
        pending: PendingAgentState,
        command: String,
        service: AccessibilityGateway,
        executor: PendingExecutor,
        runContext: AgentRunContext,
    ): AgentRunResult = when (VoiceConfirmPhraseMatcher.classify(command)) {
        VoiceConfirmPhraseMatcher.Intent.CONFIRM -> {
            val steps = pending.plannedSteps.orEmpty()
            clear()
            if (steps.isEmpty()) {
                AgentRunResult(false, "没有可执行的步骤", emptyList())
            } else {
                executor.executeLocalSteps(
                    service.context(),
                    service,
                    steps,
                    pending.originalCommand,
                    runContext,
                )
            }
        }
        VoiceConfirmPhraseMatcher.Intent.CANCEL -> {
            clear()
            AgentRunResult(true, "好的，已取消", emptyList())
        }
        VoiceConfirmPhraseMatcher.Intent.UNCLEAR ->
            waitingResult(pending.aiPrompt, needsBinaryConfirm = true)
    }

    private suspend fun handleIntentDisambiguationReply(
        pending: PendingAgentState,
        command: String,
        apiKey: String,
        service: AccessibilityGateway,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
        executor: PendingExecutor,
    ): AgentRunResult {
        val options = decodeDisambiguationOptions(pending.deferredCommand)
        val matched = options.firstOrNull { option ->
            command.contains(option.label, ignoreCase = true) ||
                command.contains(option.intentId, ignoreCase = true)
        } ?: options.firstOrNull()
        if (matched == null) {
            return waitingResult(pending.aiPrompt, needsBinaryConfirm = false)
        }
        return executor.runDisambiguatedIntent(
            command = pending.originalCommand,
            intentId = matched.intentId,
            apiKey = apiKey,
            appContext = service.context(),
            runContext = runContext,
            onProgress = onProgress,
        )
    }

    private fun waitingResult(prompt: String, needsBinaryConfirm: Boolean): AgentRunResult =
        AgentRunResult(
            success = true,
            summary = prompt,
            logs = emptyList(),
            waitingForUserConfirm = true,
            confirmPrompt = prompt,
            needsBinaryConfirm = needsBinaryConfirm,
        )

    private fun encodeDisambiguationOptions(options: List<DisambiguationOption>): String {
        val arr = JSONArray()
        options.forEach { option ->
            arr.put(
                JSONObject()
                    .put("intent", option.intentId)
                    .put("label", option.label),
            )
        }
        return arr.toString()
    }

    private fun decodeDisambiguationOptions(raw: String?): List<DisambiguationOption> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
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
}

internal interface PendingExecutor {
    suspend fun resumeUserConfirm(
        pending: PendingAgentState,
        command: String,
        apiKey: String,
        service: AccessibilityGateway,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
    ): AgentRunResult

    suspend fun executeLocalSteps(
        context: Context,
        service: AccessibilityGateway,
        steps: List<AgentAction>,
        originalCommand: String,
        runContext: AgentRunContext,
    ): AgentRunResult

    suspend fun runNewCommand(
        command: String,
        apiKey: String,
        service: AccessibilityGateway,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
    ): AgentRunResult

    suspend fun runDisambiguatedIntent(
        command: String,
        intentId: String,
        apiKey: String,
        appContext: Context,
        runContext: AgentRunContext,
        onProgress: ((Int, String) -> Unit)?,
    ): AgentRunResult
}
