package com.tetraploid.joyforold.agent.runtime

import android.app.Application
import android.content.Intent
import com.tetraploid.joyforold.MainActivity
import com.tetraploid.joyforold.agent.AgentRunResult
import com.tetraploid.joyforold.agent.NavPoiPickCodec
import com.tetraploid.joyforold.agent.PendingAbandonPhraseMatcher
import com.tetraploid.joyforold.agent.PendingKind
import com.tetraploid.joyforold.agent.RuntimePermissionKind
import com.tetraploid.joyforold.agent.RuntimePermissionPrompt
import com.tetraploid.joyforold.agent.VoiceConfirmPhraseMatcher
import com.tetraploid.joyforold.agent.VoiceFollowUpDetector
import com.tetraploid.joyforold.agent.ContextConsentStore
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.speech.AsrSpeakerAdaptation
import com.tetraploid.joyforold.speech.AsrSpeakerProfileStore
import com.tetraploid.joyforold.speech.BargeInSpeakOutcome
import com.tetraploid.joyforold.speech.DoubaoAsrClient
import com.tetraploid.joyforold.speech.DoubaoSpeechInput
import com.tetraploid.joyforold.speech.AndroidTtsOutput
import com.tetraploid.joyforold.speech.SpeechEchoFilter
import com.tetraploid.joyforold.speech.VoiceBargeInHelper
import com.tetraploid.joyforold.speech.VoiceTurnCoordinator
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.util.NetworkStatus
import com.tetraploid.joyforold.voice.WakeEarconPlayer
import com.tetraploid.joyforold.wakeword.WakeChainedAudioBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音输入 / TTS / 打断与会话延续。
 */
internal class VoiceSessionController(
    private val mainScope: CoroutineScope,
    private val agentScope: CoroutineScope,
    private val state: AgentStateAccessor,
    private val appendLog: (String) -> Unit,
    private val syncOverlay: () -> Unit,
    private val blocksLocalAgent: () -> Boolean,
    private val hasRecordAudioPermission: (Application) -> Boolean,
    private val requestRecordAudio: (RuntimePermissionPrompt) -> Unit,
    private val appInForeground: () -> Boolean,
    private var applicationProvider: () -> Application?,
    private var apiKeyStoreProvider: () -> ApiKeyStore?,
    private var asrSpeakerProfileProvider: () -> AsrSpeakerProfileStore?,
    private var wakeWordControllerProvider: () -> WakeWordController?,
    private var androidTtsOutputProvider: () -> AndroidTtsOutput?,
    private val orchestratorBridge: VoiceOrchestratorBridge,
    private val onRunAgent: (Application, Boolean?) -> Unit,
    private val onHandleStandaloneResult: suspend (Application, AgentRunResult) -> Unit,
    private val onClearPendingConfirmUI: () -> Unit,
) {
    private var asrClient: DoubaoAsrClient? = null
    private var speechInput: DoubaoSpeechInput? = null
    private var voiceTurnCoordinator: VoiceTurnCoordinator? = null
    private var voiceConfirmReplyMode = false
    private var voiceReplyApplication: Application? = null
    private var cachedAsrParams: AsrParams? = null
    private val recentVoicePrompts = ArrayDeque<String>(6)

    @Volatile
    var sessionActive = false

    fun bind(
        applicationProvider: () -> Application?,
        apiKeyStoreProvider: () -> ApiKeyStore?,
        asrSpeakerProfileProvider: () -> AsrSpeakerProfileStore?,
        wakeWordControllerProvider: () -> WakeWordController?,
        androidTtsOutputProvider: () -> AndroidTtsOutput?,
    ) {
        this.applicationProvider = applicationProvider
        this.apiKeyStoreProvider = apiKeyStoreProvider
        this.asrSpeakerProfileProvider = asrSpeakerProfileProvider
        this.wakeWordControllerProvider = wakeWordControllerProvider
        this.androidTtsOutputProvider = androidTtsOutputProvider
    }

    fun resetConfirmReplyMode() {
        voiceConfirmReplyMode = false
        voiceReplyApplication = null
    }

    fun startVoiceInput() {
        if (blocksLocalAgent()) {
            appendLog("协助进行中：请在协作页远程操作")
            return
        }
        if (state.read().waitingForUserConfirm) {
            applicationProvider()?.let { startVoiceReplyToConfirm(it) }
            return
        }
        sessionActive = true
        startVoiceInputInternal(
            confirmReplyMode = false,
            application = null,
            skipPrompt = true,
        )
    }

    fun resumeWakeWordVoiceSession() {
        val app = applicationProvider() ?: return
        if (blocksLocalAgent()) return
        if (state.read().isRunning || state.read().isListening) return
        sessionActive = true
        mainScope.launch(Dispatchers.Main.immediate) {
            startVoiceInputInternal(
                confirmReplyMode = false,
                application = app,
                wakeWordActivation = true,
            )
        }
    }

    fun startFromWakeWord(application: Application) {
        sessionActive = true
        mainScope.launch(Dispatchers.Main.immediate) {
            startVoiceInputInternal(
                confirmReplyMode = false,
                application = application,
                wakeWordActivation = true,
            )
        }
    }

    fun startVoiceReplyToConfirm(application: Application) {
        if (!state.read().waitingForUserConfirm) return
        if (state.read().isRunning) return
        sessionActive = true
        mainScope.launch {
            restartPendingVoiceListen(application, speakReprompt = false)
        }
    }

    private suspend fun restartPendingVoiceListen(
        application: Application,
        speakReprompt: Boolean,
    ) {
        voiceTurnCoordinator?.cancelVoice()
        speechInput?.cancelActiveSession()
        androidTtsOutputProvider()?.awaitIdle()
        if (speakReprompt) {
            val hint = when (orchestratorBridge.peekPendingKind()) {
                PendingKind.NAV_POI_PICK ->
                    "没听清您选的地点，请说第几个，或直接说学校或店名"
                PendingKind.INTENT_DISAMBIGUATION ->
                    "没听清，请说出或点选要执行的操作"
                else -> "没有听清，请再说一次"
            }
            voiceTurnCoordinator?.speakResult(hint)
            androidTtsOutputProvider()?.awaitIdle()
        }
        startVoiceInputInternal(
            confirmReplyMode = true,
            application = application,
            skipPrompt = !speakReprompt &&
                orchestratorBridge.peekPendingKind() !in setOf(
                    PendingKind.NAV_POI_PICK,
                    PendingKind.INTENT_DISAMBIGUATION,
                ),
        )
    }

    fun startVoiceOpenFollowUp(application: Application) {
        if (!state.read().waitingForUserConfirm) return
        if (state.read().isListening || state.read().isRunning) return
        sessionActive = true
        mainScope.launch {
            androidTtsOutputProvider()?.awaitIdle()
            startVoiceInputInternal(
                confirmReplyMode = true,
                application = application,
                skipPrompt = false,
            )
        }
    }

    fun stopVoiceInput() {
        val input = speechInput ?: run {
            state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle) }
            syncOverlay()
            return
        }
        mainScope.launch {
            input.stop { finalText ->
                voiceTurnCoordinator?.markProcessing()
                state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing) }
                handleVoiceFinalText(finalText, fromAutoStop = false)
                voiceTurnCoordinator?.markIdle()
                syncOverlay()
            }
        }
    }

    fun stopVoiceInputAndRunAgent(application: Application) {
        sessionActive = true
        val input = speechInput ?: run {
            state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle) }
            syncOverlay()
            return
        }
        mainScope.launch {
            input.stop { finalText ->
                voiceTurnCoordinator?.markProcessing()
                state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing) }
                handleVoiceFinalText(finalText, fromAutoStop = false, forceRun = true, application = application)
                voiceTurnCoordinator?.markIdle()
                syncOverlay()
            }
        }
    }

    fun abortInput() {
        voiceTurnCoordinator?.cancelVoice()
        speechInput?.cancelActiveSession()
        state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle) }
        syncOverlay()
    }

    fun speakStatus(text: String, flush: Boolean = false) {
        val concise = text.trim().take(120)
        if (concise.isBlank()) return
        recordVoicePrompt(concise)
        androidTtsOutputProvider()?.speak(concise, flush = flush)
    }

    fun recordVoicePrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        recentVoicePrompts.removeAll { it == trimmed }
        recentVoicePrompts.addLast(trimmed)
        while (recentVoicePrompts.size > 6) {
            recentVoicePrompts.removeFirst()
        }
    }

    suspend fun speakPromptWithOptionalBargeIn(prompt: String): ByteArray? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null
        val tts = androidTtsOutputProvider() ?: return null
        if (!state.read().voiceBargeInEnabled || !sessionActive) {
            tts.speakAndAwait(trimmed, flush = true)
            return null
        }
        val app = applicationProvider() ?: return null.also { tts.speakAndAwait(trimmed, flush = true) }
        if (state.read().wakeWordEnabled) {
            wakeWordControllerProvider()?.pauseForMicSharing()
        }
        wakeWordControllerProvider()?.awaitMicReleased()
        return when (val outcome = VoiceBargeInHelper.speakWithBargeIn(app, tts, trimmed)) {
            is BargeInSpeakOutcome.BargedIn -> outcome.preRollPcm
            BargeInSpeakOutcome.Completed -> null
        }
    }

    fun applyBargeInPreRoll(preRoll: ByteArray?) {
        if (preRoll != null && preRoll.isNotEmpty()) {
            ensureAsrClient()?.setPreRollPcm(preRoll)
        }
    }

    fun continueConversationAfterAgentResult(application: Application, result: AgentRunResult) {
        if (result.waitingForUserConfirm) {
            sessionActive = true
            mainScope.launch {
                androidTtsOutputProvider()?.awaitIdle()
                val kind = orchestratorBridge.peekPendingKind()
                val isPickList = kind == PendingKind.NAV_POI_PICK ||
                    kind == PendingKind.INTENT_DISAMBIGUATION
                startVoiceInputInternal(
                    confirmReplyMode = true,
                    application = application,
                    skipPrompt = !isPickList,
                )
            }
            return
        }
        if (!sessionActive) {
            scheduleWakeWordRestoreIfIdle()
            return
        }
        mainScope.launch {
            androidTtsOutputProvider()?.awaitIdle()
            when {
                result.success && shouldContinueConversation(result.summary) -> {
                    startVoiceInputInternal(
                        confirmReplyMode = false,
                        application = application,
                        skipPrompt = true,
                    )
                }
                else -> scheduleWakeWordRestoreIfIdle()
            }
        }
    }

    fun scheduleWakeWordRestoreIfIdle() {
        val snapshot = state.read()
        if (!sessionActive && !snapshot.isListening && !snapshot.isRunning) {
            wakeWordControllerProvider()?.ensureRunning()
        }
    }

    fun invalidateAsrClient() {
        asrClient?.shutdown()
        asrClient = null
        speechInput = null
        voiceTurnCoordinator = null
        cachedAsrParams = null
    }

    private fun startVoiceInputInternal(
        confirmReplyMode: Boolean,
        application: Application?,
        skipPrompt: Boolean = false,
        wakeWordActivation: Boolean = false,
    ) {
        if (state.read().isListening) return
        val app = application ?: applicationProvider()
        if (app != null && !hasRecordAudioPermission(app)) {
            sessionActive = false
            requestRecordAudioForVoiceInput(app)
            return
        }
        app?.let { ctx ->
            NetworkStatus.offlineHint(ctx)?.let { hint ->
                appendLog(hint)
                speakStatus("网络不可用，请检查 WiFi 或流量")
                return
            }
        }
        val coordinator = ensureVoiceStack() ?: run {
            appendLog("语音识别未配置：请在下方填写豆包 ASR 配置，或写入 local.properties")
            return
        }
        if (state.read().wakeWordEnabled) {
            wakeWordControllerProvider()?.pauseForMicSharing()
        }
        if (wakeWordActivation) {
            app?.let { WakeEarconPlayer.play(it) }
            ensureAsrClient()?.setPreRollPcm(WakeChainedAudioBridge.takeAndClear())
        }
        voiceConfirmReplyMode = confirmReplyMode
        voiceReplyApplication = application
        val prompt = when {
            skipPrompt || wakeWordActivation -> null
            confirmReplyMode -> state.read().confirmPrompt ?: "请回答确认问题"
            else -> "请说出您的指令"
        }
        appendLog(
            when {
                confirmReplyMode && state.read().voiceBargeInEnabled ->
                    "正在播报问题，可随时说话打断"
                confirmReplyMode -> "请先听完问题，再语音回答"
                wakeWordActivation -> "唤醒后继续听指令"
                state.read().voiceBargeInEnabled -> "正在提示，可随时说话打断"
                else -> "开始语音识别"
            },
        )
        prompt?.let { recordVoicePrompt(it) }
        agentScope.launch {
            withContext(Dispatchers.Main.immediate) {
                state.update {
                    it.copy(
                        isListening = prompt.isNullOrBlank(),
                        speechText = "",
                        voiceInteractionState = if (prompt.isNullOrBlank()) {
                            VoiceInteractionState.Listening
                        } else {
                            VoiceInteractionState.SpeakingPrompt
                        },
                    )
                }
                syncOverlay()
            }
            if (state.read().wakeWordEnabled) {
                wakeWordControllerProvider()?.awaitMicReleased()
            } else if (state.read().voiceBargeInEnabled && !prompt.isNullOrBlank()) {
                wakeWordControllerProvider()?.awaitMicReleased()
            }
            coordinator.speakPromptThenListen(
                prompt = prompt,
                session = SpeechInputSession(
                    shortUtterance = confirmReplyMode,
                    onPartialText = { text ->
                        if (text.isBlank()) return@SpeechInputSession
                        val cleaned = filterVoiceRecognition(text)
                        if (cleaned.isBlank()) return@SpeechInputSession
                        mainScope.launch(Dispatchers.Main.immediate) {
                            state.update { it.copy(speechText = cleaned, command = cleaned) }
                        }
                    },
                    onFinalText = { text ->
                        mainScope.launch {
                            voiceTurnCoordinator?.markProcessing()
                            state.update {
                                it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing)
                            }
                            handleVoiceFinalText(text, fromAutoStop = true)
                            voiceTurnCoordinator?.markIdle()
                        }
                    },
                    onError = { error ->
                        mainScope.launch {
                            val recovered = state.read().speechText.trim()
                            if (recovered.isNotBlank()) {
                                appendLog("语音识别收尾异常，已使用识别结果：$recovered")
                                voiceTurnCoordinator?.markProcessing()
                                state.update {
                                    it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing)
                                }
                                handleVoiceFinalText(recovered, fromAutoStop = true)
                                voiceTurnCoordinator?.markIdle()
                                return@launch
                            }
                            appendLog(error)
                            speakStatus("语音识别失败，请重试")
                            resetConfirmReplyMode()
                            state.update {
                                it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle)
                            }
                            wakeWordControllerProvider()?.ensureRunning()
                            syncOverlay()
                        }
                    },
                ),
            )
        }
    }

    private fun handleVoiceFinalText(
        text: String,
        fromAutoStop: Boolean,
        forceRun: Boolean = false,
        application: Application? = null,
    ) {
        val raw = text.ifBlank { state.read().speechText }
        val merged = filterVoiceRecognition(raw)
        state.update { it.copy(isListening = false, speechText = merged, command = merged) }

        val app = application ?: voiceReplyApplication
        val isConfirmReply = voiceConfirmReplyMode
        resetConfirmReplyMode()

        appendLog(
            if (merged.isBlank()) "语音识别结束：未识别到文本" else "语音识别：$merged",
        )

        if (merged.isBlank()) {
            if (isConfirmReply && app != null && state.read().waitingForUserConfirm) {
                appendLog("未听清，请再说一次")
                mainScope.launch {
                    restartPendingVoiceListen(app, speakReprompt = true)
                }
            } else {
                wakeWordControllerProvider()?.ensureRunning()
            }
            return
        }

        if (isConfirmReply && state.read().waitingForUserConfirm) {
            val pendingKind = orchestratorBridge.peekPendingKind()
            when (pendingKind) {
                PendingKind.TASK_ABANDON -> when (PendingAbandonPhraseMatcher.classify(merged)) {
                    PendingAbandonPhraseMatcher.Intent.ABANDON -> appendLog("用户选择放弃：$merged")
                    PendingAbandonPhraseMatcher.Intent.CONTINUE -> appendLog("用户选择继续：$merged")
                    PendingAbandonPhraseMatcher.Intent.UNCLEAR -> {
                        appendLog("放弃/继续未听清：$merged")
                        mainScope.launch {
                            voiceTurnCoordinator?.speakResult("没太听清，请说「放弃」或「继续」")
                            startVoiceReplyToConfirm(app!!)
                        }
                        return
                    }
                }
                PendingKind.ROUTE_CLARIFY, PendingKind.LOCAL_PREVIEW -> when (VoiceConfirmPhraseMatcher.classify(merged)) {
                    VoiceConfirmPhraseMatcher.Intent.CANCEL -> {
                        appendLog("用户取消：$merged")
                        speakStatus("好的，已取消")
                        onClearPendingConfirmUI()
                        wakeWordControllerProvider()?.ensureRunning()
                        return
                    }
                    VoiceConfirmPhraseMatcher.Intent.CONFIRM -> appendLog("用户确认：$merged")
                    VoiceConfirmPhraseMatcher.Intent.UNCLEAR -> {
                        if (VoiceFollowUpDetector.looksLikeNewCommand(merged)) {
                            appendLog("识别为新指令，结束路由确认：$merged")
                            orchestratorBridge.clearPendingUserReply()
                            state.update {
                                it.copy(
                                    waitingForUserConfirm = false,
                                    confirmPrompt = null,
                                    needsBinaryConfirm = false,
                                )
                            }
                        } else {
                            appendLog("确认回答含糊：$merged")
                            mainScope.launch {
                                voiceTurnCoordinator?.speakResult("没太听清，请说「确认」还是「取消」？")
                                startVoiceReplyToConfirm(app!!)
                            }
                            return
                        }
                    }
                }
                PendingKind.INTENT_DISAMBIGUATION -> {
                    val options = orchestratorBridge.peekDisambiguationOptions()
                    val matched = options.firstOrNull { option ->
                        merged.contains(option.label, ignoreCase = true) ||
                            merged.contains(option.intentId, ignoreCase = true)
                    }
                    if (matched != null) {
                        appendLog("用户选择意图：${matched.label}")
                        mainScope.launch {
                            val result = orchestratorBridge.runDisambiguatedIntent(
                                command = orchestratorBridge.peekPendingOriginalCommand().orEmpty(),
                                intentId = matched.intentId,
                                apiKey = state.read().apiKey.ifBlank {
                                    apiKeyStoreProvider()?.getApiKey().orEmpty()
                                },
                                appContext = app!!,
                            )
                            onHandleStandaloneResult(app, result)
                        }
                        return
                    }
                    appendLog("消歧选择未听清：$merged")
                    mainScope.launch {
                        restartPendingVoiceListen(app!!, speakReprompt = true)
                    }
                    return
                }
                PendingKind.NAV_POI_PICK -> {
                    val options = orchestratorBridge.peekDisambiguationOptions()
                    val matched = NavPoiPickCodec.matchReply(merged, options)
                    if (matched != null) {
                        appendLog("用户选择地点：${matched.label}")
                        mainScope.launch {
                            val result = orchestratorBridge.runNavPoiPick(
                                poiIntentId = matched.intentId,
                                originalCommand = orchestratorBridge.peekPendingOriginalCommand().orEmpty(),
                                appContext = app!!,
                            )
                            onHandleStandaloneResult(app, result)
                        }
                        return
                    }
                    appendLog("地点选择未听清：$merged")
                    mainScope.launch {
                        restartPendingVoiceListen(app!!, speakReprompt = true)
                    }
                    return
                }
                PendingKind.CONTEXT_CONSENT -> {
                    orchestratorBridge.clearPendingUserReply()
                    onClearPendingConfirmUI()
                    speakStatus(ContextConsentStore.SETTINGS_HINT)
                    return
                }
                PendingKind.USER_CONFIRM -> {
                    val needsBinary = orchestratorBridge.peekPendingNeedsBinaryConfirm()
                    val intent = VoiceConfirmPhraseMatcher.classify(merged)

                    if (intent == VoiceConfirmPhraseMatcher.Intent.CANCEL) {
                        appendLog("用户取消：$merged")
                        speakStatus("好的，已取消")
                        onClearPendingConfirmUI()
                        wakeWordControllerProvider()?.ensureRunning()
                        return
                    }

                    if (!needsBinary) {
                        if (VoiceFollowUpDetector.looksLikeNewCommand(merged)) {
                            appendLog("识别为新指令，结束等待：$merged")
                            orchestratorBridge.clearPendingUserReply()
                            state.update {
                                it.copy(
                                    waitingForUserConfirm = false,
                                    confirmPrompt = null,
                                    needsBinaryConfirm = false,
                                )
                            }
                        } else {
                            appendLog("用户回答：$merged")
                        }
                    } else {
                        when (intent) {
                            VoiceConfirmPhraseMatcher.Intent.CONFIRM -> appendLog("用户确认：$merged")
                            VoiceConfirmPhraseMatcher.Intent.UNCLEAR -> {
                                if (VoiceFollowUpDetector.looksLikeNewCommand(merged)) {
                                    appendLog("识别为新指令，放弃待确认操作：$merged")
                                    orchestratorBridge.clearPendingUserReply()
                                    state.update {
                                        it.copy(
                                            waitingForUserConfirm = false,
                                            confirmPrompt = null,
                                            needsBinaryConfirm = false,
                                        )
                                    }
                                } else {
                                    appendLog("确认回答含糊：$merged")
                                    mainScope.launch {
                                        voiceTurnCoordinator?.speakResult("没太听清，请说「发送」还是「取消」？")
                                        startVoiceReplyToConfirm(app!!)
                                    }
                                    return
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        val runApp = app ?: applicationProvider()
        if (runApp == null) {
            wakeWordControllerProvider()?.ensureRunning()
            return
        }
        val shouldRunAgent = forceRun || fromAutoStop || isConfirmReply
        if (!shouldRunAgent) {
            wakeWordControllerProvider()?.ensureRunning()
            return
        }
        appendLog("继续执行：$merged")
        val resumePending = isConfirmReply && state.read().waitingForUserConfirm
        onRunAgent(runApp, resumePending)
    }

    private fun filterVoiceRecognition(text: String): String {
        val echoStripped = SpeechEchoFilter.stripEcho(text, recentVoicePrompts.toList())
        val wakePhrase = state.read().wakeWordPhrase.ifBlank {
            asrSpeakerProfileProvider()?.wakePhrase().orEmpty()
        }
        val corrections = asrSpeakerProfileProvider()?.loadCorrections().orEmpty()
        return AsrSpeakerAdaptation.adapt(echoStripped, wakePhrase, corrections)
    }

    private fun ensureVoiceStack(): VoiceTurnCoordinator? {
        val client = ensureAsrClient() ?: return null
        val input = DoubaoSpeechInput(client)
        speechInput = input
        val tts = androidTtsOutputProvider() ?: return null
        val app = applicationProvider()
        val coordinator = VoiceTurnCoordinator(
            ttsOutput = tts,
            speechInput = input,
            onStateChanged = { voiceState ->
                mainScope.launch(Dispatchers.Main.immediate) {
                    state.update {
                        it.copy(
                            voiceInteractionState = voiceState,
                            isListening = voiceState == VoiceInteractionState.Listening,
                        )
                    }
                    syncOverlay()
                }
            },
            awaitTtsIdle = { tts.awaitIdle() },
            speakPromptBlocking = { prompt ->
                if (!state.read().voiceBargeInEnabled || app == null) {
                    tts.speakAndAwait(prompt, flush = true)
                    BargeInSpeakOutcome.Completed
                } else {
                    if (state.read().wakeWordEnabled) {
                        wakeWordControllerProvider()?.pauseForMicSharing()
                    }
                    wakeWordControllerProvider()?.awaitMicReleased()
                    VoiceBargeInHelper.speakWithBargeIn(app, tts, prompt)
                }
            },
            onBargeInPreRoll = { pcm -> client.setPreRollPcm(pcm) },
        )
        voiceTurnCoordinator = coordinator
        return coordinator
    }

    private fun requestRecordAudioForVoiceInput(application: Application) {
        appendLog("语音输入需要麦克风权限")
        requestRecordAudio(
            RuntimePermissionPrompt(
                kind = RuntimePermissionKind.RecordAudio,
                title = "需要麦克风权限",
                message = "语音输入需要麦克风权限，请允许后重试。",
                resumeVoiceInputAfterPermission = true,
            ),
        )
        speakStatus("需要麦克风权限才能语音输入")
        if (!appInForeground()) {
            val intent = Intent(application, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            application.startActivity(intent)
        }
    }

    private fun ensureAsrClient(): DoubaoAsrClient? {
        val params = resolveAsrParams() ?: return null
        if (asrClient != null && cachedAsrParams == params) return asrClient
        asrClient?.shutdown()
        asrClient = DoubaoAsrClient(
            apiKey = params.apiKey,
            appId = params.appId,
            accessToken = params.accessToken,
            resourceId = params.resourceId,
        )
        cachedAsrParams = params
        return asrClient
    }

    private fun resolveAsrParams(): AsrParams? {
        val current = state.read()
        val store = apiKeyStoreProvider()
        val apiKey = current.asrApiKey.ifBlank { store?.getAsrApiKey().orEmpty() }.orEmpty()
        val appId = current.asrAppId.ifBlank { store?.getAsrAppId().orEmpty() }.orEmpty()
        val accessToken = current.asrAccessToken.ifBlank { store?.getAsrAccessToken().orEmpty() }.orEmpty()
        val resourceId = current.asrResourceId.ifBlank { store?.getAsrResourceId().orEmpty() }.orEmpty()
        val hasNewApiKey = apiKey.isNotBlank()
        val hasLegacyAuth = appId.isNotBlank() && accessToken.isNotBlank()
        if (!hasNewApiKey && !hasLegacyAuth) return null
        return AsrParams(apiKey, appId, accessToken, resourceId)
    }

    private fun shouldContinueConversation(summary: String): Boolean {
        val text = summary.trim()
        if (text.isEmpty()) return false
        return text.contains('?') || text.contains('？') ||
            text.contains("有什么可以帮") || text.contains("请说") ||
            text.contains("请告诉") || text.contains("还有什么")
    }

    private data class AsrParams(
        val apiKey: String,
        val appId: String,
        val accessToken: String,
        val resourceId: String,
    )
}

internal interface VoiceOrchestratorBridge {
    fun peekPendingKind(): PendingKind
    fun peekPendingNeedsBinaryConfirm(): Boolean
    fun peekDisambiguationOptions(): List<com.tetraploid.joyforold.agent.DisambiguationOption>
    fun peekPendingOriginalCommand(): String?
    fun clearPendingUserReply()
    suspend fun runDisambiguatedIntent(
        command: String,
        intentId: String,
        apiKey: String,
        appContext: android.content.Context,
    ): AgentRunResult

    suspend fun runNavPoiPick(
        poiIntentId: String,
        originalCommand: String,
        appContext: android.content.Context,
    ): AgentRunResult
}
