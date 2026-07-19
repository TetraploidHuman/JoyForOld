package com.tetraploid.joyforold.agent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import com.tetraploid.joyforold.accessibility.AccessibilityPermission
import com.tetraploid.joyforold.accessibility.AccessibilityGateways
import com.tetraploid.joyforold.accessibility.UiTreeLogcatStore
import com.tetraploid.joyforold.core.AssistSessionStarter
import com.tetraploid.joyforold.core.AssistSessionStarters
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.collaboration.AssistPairingStore
import com.tetraploid.joyforold.collaboration.AssistSessionPhase
import com.tetraploid.joyforold.agent.runtime.AgentStateAccessor
import com.tetraploid.joyforold.agent.runtime.AssistRuntimeBridge
import com.tetraploid.joyforold.agent.runtime.VoiceSessionController
import com.tetraploid.joyforold.ime.JoyImeHelper
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission
import com.tetraploid.joyforold.overlay.VisionOverlaySuppressor
import com.tetraploid.joyforold.overlay.VisionOverlaySuppressors
import com.tetraploid.joyforold.offline.nlu.OfflineNluModelManager
import com.tetraploid.joyforold.preset.PresetCommand
import com.tetraploid.joyforold.preset.PresetCommandStore
import com.tetraploid.joyforold.preset.PresetTextNormalizer
import com.tetraploid.joyforold.privacy.SafeLog
import com.tetraploid.joyforold.agent.runtime.WakeWordController
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.speech.AndroidTtsOutput
import com.tetraploid.joyforold.speech.AsrSpeakerProfileStore
import com.tetraploid.joyforold.system.ContactResolver
import com.tetraploid.joyforold.system.NotificationAccessPermission
import com.tetraploid.joyforold.wakeword.SherpaOnnxModelManager
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import com.tetraploid.joyforold.wakeword.WakeWordService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RuntimePermissionKind {
    RecordAudio,
    ReadContacts,
    Accessibility,
}

data class RuntimePermissionPrompt(
    val kind: RuntimePermissionKind,
    val title: String,
    val message: String,
    val resumeEnableWakeWord: Boolean = false,
    val resumeWakeWordVoice: Boolean = false,
    val resumeVoiceInputAfterPermission: Boolean = false,
)

class AgentRuntime(
    private val orchestrator: AgentOrchestrator,
    private val apiKeyStore: ApiKeyStore,
    private val wakeWordStore: WakeWordConfigStore,
    private val memoryStore: AgentMemoryStore,
    private val androidTtsOutput: AndroidTtsOutput,
    private val caregiverStore: CaregiverSupportStore,
    private val presetStore: PresetCommandStore,
    private val contextConsentStore: ContextConsentStore,
    private val voiceInteractionConfigStore: VoiceInteractionConfigStore,
    private val asrSpeakerProfileStore: AsrSpeakerProfileStore,
    private val proactiveAssistantEngine: ProactiveAssistantEngine,
    private val visionDebugStore: VisionDebugStore,
    private val uiTreeLogcatStore: UiTreeLogcatStore,
    private val assistPairingStore: AssistPairingStore,
) : VisionOverlaySuppressor {
    init {
        VisionOverlaySuppressors.install(this)
    }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentJob: Job? = null
    private var runContext: AgentRunContext? = null
    private var application: Application? = null
    private val conversationCards = ConversationCardSession()
    private var wakeWordController: WakeWordController? = null
    private var assistBridge: AssistRuntimeBridge? = null
    private var voiceSessionController: VoiceSessionController? = null

    @Volatile
    private var appInForeground: Boolean = false

    private var accessibilityStateListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    @Volatile
    private var visionOverlaySuppressionDepth = 0

    /** 视觉 Agent 在外部应用（微信等）执行期间禁止浮层复现，避免挡截图与 tap。 */
    @Volatile
    private var visionAgentActive = false

    @Volatile
    private var bootstrapped = false

    private val _state = MutableStateFlow(AgentUiState())
    private val stateAccessor = AgentStateAccessor(_state)
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private fun ensureControllers() {
        if (wakeWordController != null) return
        wakeWordController = WakeWordController(
            mainScope = mainScope,
            state = stateAccessor,
            appendLog = ::appendLog,
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            requestRecordAudioForWakeWord = ::showPermissionPrompt,
            applicationProvider = { application },
            storeProvider = { wakeWordStore },
            speakerProfileProvider = { asrSpeakerProfileStore },
        )
        assistBridge = AssistRuntimeBridge(
            state = stateAccessor,
            agentScope = agentScope,
            onAssistModeChanged = ::setAssistMode,
            onRemoteCommand = ::submitRemoteAssistCommand,
        )
        voiceSessionController = VoiceSessionController(
            mainScope = mainScope,
            agentScope = agentScope,
            state = stateAccessor,
            appendLog = ::appendLog,
            syncOverlay = ::syncOverlayVisibility,
            blocksLocalAgent = ::blocksLocalAgentForCaregiverAssist,
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            requestRecordAudio = ::showPermissionPrompt,
            appInForeground = { appInForeground },
            applicationProvider = { application },
            apiKeyStoreProvider = { apiKeyStore },
            asrSpeakerProfileProvider = { asrSpeakerProfileStore },
            wakeWordControllerProvider = { wakeWordController },
            androidTtsOutputProvider = { androidTtsOutput },
            orchestratorBridge = orchestratorVoiceBridge,
            onRunAgent = { app, resume -> runAgent(app, resume) },
            onHandleStandaloneResult = { app, result -> handleStandaloneAgentResult(app, result) },
            onClearPendingConfirmUI = ::clearPendingConfirmUI,
        )
    }

    private val orchestratorVoiceBridge = object : com.tetraploid.joyforold.agent.runtime.VoiceOrchestratorBridge {
        override fun peekPendingKind() = orchestrator.peekPendingKind()
        override fun peekPendingNeedsBinaryConfirm() = orchestrator.peekPendingNeedsBinaryConfirm()
        override fun peekDisambiguationOptions() = orchestrator.peekDisambiguationOptions()
        override fun peekPendingOriginalCommand() = orchestrator.peekPendingOriginalCommand()
        override fun clearPendingUserReply() = orchestrator.clearPendingUserReply()
        override suspend fun runDisambiguatedIntent(
            command: String,
            intentId: String,
            apiKey: String,
            appContext: android.content.Context,
        ) = orchestrator.runDisambiguatedIntent(
            command = command,
            intentId = intentId,
            apiKey = apiKey,
            appContext = appContext,
            runContext = AgentRunContext(),
        )

        override suspend fun runNavPoiPick(
            poiIntentId: String,
            originalCommand: String,
            appContext: android.content.Context,
        ) = orchestrator.runNavPoiPick(
            poiIntentId = poiIntentId,
            originalCommand = originalCommand,
            appContext = appContext,
            runContext = AgentRunContext(),
        )
    }

    private fun voiceSession(): VoiceSessionController? {
        ensureControllers()
        return voiceSessionController
    }

    fun initIfNeeded(application: Application) {
        this.application = application.applicationContext as Application
        if (!bootstrapped) {
            bootstrapped = true
            AssistSessionStarters.delegate = AssistSessionStarter { startElderAssistSession() }
            androidTtsOutput.ensureReady()
            ensureControllers()
            assistBridge!!.attachSessionManager(application, assistPairingStore)
            refreshMemories()
            restorePendingUiIfNeeded()
            mainScope.launch(Dispatchers.IO) {
                OfflineNluModelManager.getClassifier(application)
            }
            _state.update {
                val contacts = caregiverStore.loadFamilyContacts()
                val daughter = contacts.firstOrNull { it.alias == "女儿" }
                val son = contacts.firstOrNull { it.alias == "儿子" }
                val emergency = contacts.firstOrNull { it.alias == "紧急联系人" }
                val goHomePreset = presetStore.loadPresets().firstOrNull { it.action == "navigate_home" }
                it.copy(
                    apiKey = apiKeyStore.getApiKey(),
                    modelName = apiKeyStore.getModel(),
                    asrApiKey = apiKeyStore.getAsrApiKey(),
                    asrAppId = apiKeyStore.getAsrAppId(),
                    asrAccessToken = apiKeyStore.getAsrAccessToken(),
                    asrResourceId = apiKeyStore.getAsrResourceId(),
                    wakeWord = it.wakeWord.copy(
                        enabled = wakeWordStore.isEnabled(),
                        phrase = wakeWordStore.getPhrase(),
                        running = wakeWordStore.isEnabled() && WakeWordService.isRunning,
                        keywordScore = wakeWordStore.getKeywordScore(),
                        keywordThreshold = wakeWordStore.getKeywordThreshold(),
                        confirmHits = wakeWordStore.getConfirmHitCount(),
                        preset = wakeWordStore.getPreset(),
                        modelVersion = SherpaOnnxModelManager.MODEL_VERSION,
                        calibrated = wakeWordStore.isCalibrated(),
                        sileroVadEnabled = wakeWordStore.isSileroVadEnabled(),
                        secondStageEnabled = wakeWordStore.isSecondStageEnabled(),
                    ),
                    daughterPhone = daughter?.phoneNumber.orEmpty(),
                    sonPhone = son?.phoneNumber.orEmpty(),
                    emergencyPhone = emergency?.phoneNumber.orEmpty(),
                    emergencyMessage = caregiverStore.loadEmergencyMessage(),
                    homeAddress = caregiverStore.loadHomeAddress(),
                    presetPhraseGoHome = goHomePreset?.aliases?.joinToString(", ") ?: "我要回家, 导航回家, 送我回家",
                    permissions = it.permissions.copy(
                        recordAudioGranted = hasRecordAudioPermission(application),
                        readContactsGranted = ContactResolver.hasContactsPermission(application),
                        notificationAccessGranted = NotificationAccessPermission.isEnabled(application),
                    ),
                    cloudContextConsentGranted = contextConsentStore.hasConsented(),
                    voiceBargeInEnabled = voiceInteractionConfigStore.isBargeInEnabled(),
                    visionDebugEnabled = visionDebugStore.isEnabled(),
                    visionDebugFrames = visionDebugStore.listFrames(),
                    uiTreeLogcatEnabled = uiTreeLogcatStore.isEnabled(),
                )
            }
            // 服务若已连接，同步开关状态
            AccessibilityGateways.current?.setContinuousUiTreeLogcatEnabled(uiTreeLogcatStore.isEnabled())
            wakeWordController?.preloadModelsIfNeeded()
            wakeWordController?.runMigrationsIfNeeded()
            wakeWordController?.syncService()
            refreshSuggestionChips()
        }
        ensureAccessibilityStateListener(application)
        refreshAccessibilityState()
    }

    fun refreshAccessibilityState() {
        val app = application
        val settingEnabled = app?.let { AccessibilityPermission.isSettingEnabled(it) }
            ?: AccessibilityPermission.isServiceConnected()
        val connected = AccessibilityPermission.isServiceConnected()
        val whitelistReaderEnabled = app?.let { AccessibilityPermission.isWhitelistReaderSettingEnabled(it) }
            ?: false
        val whitelistReaderConnected = AccessibilityPermission.isWhitelistReaderConnected()
        val imeEnabled = app?.let { JoyImeHelper.isEnabled(it) } ?: false
        val imeDefault = app?.let { JoyImeHelper.isSelectedAsDefault(it) } ?: false
        _state.update {
            it.copy(
                permissions = it.permissions.copy(
                    accessibilityEnabled = settingEnabled,
                    accessibilityServiceConnected = connected,
                    accessibilityWhitelistReaderEnabled = whitelistReaderEnabled,
                    accessibilityWhitelistReaderConnected = whitelistReaderConnected,
                    joyImeEnabled = imeEnabled,
                    joyImeSelectedAsDefault = imeDefault,
                    recordAudioGranted = app?.let { ctx -> hasRecordAudioPermission(ctx) }
                        ?: it.recordAudioGranted,
                    readContactsGranted = app?.let { ctx -> ContactResolver.hasContactsPermission(ctx) }
                        ?: it.readContactsGranted,
                    notificationAccessGranted = app?.let { ctx ->
                        NotificationAccessPermission.isEnabled(ctx)
                    } ?: it.notificationAccessGranted,
                ),
                wakeWord = it.wakeWord.copy(
                    running = it.wakeWordEnabled && WakeWordService.isRunning,
                ),
            )
        }
    }

    private fun ensureAccessibilityStateListener(application: Application) {
        if (accessibilityStateListener != null) return
        val manager = application.getSystemService(AccessibilityManager::class.java) ?: return
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            refreshAccessibilityState()
        }
        accessibilityStateListener = listener
        manager.addAccessibilityStateChangeListener(listener)
    }

    fun onReadContactsPermissionResult(application: Application, granted: Boolean) {
        initIfNeeded(application)
        _state.update { it.updatePermissions { p -> p.copy(readContactsGranted = granted) } }
        if (granted) {
            appendLog("联系人权限已授予，可按姓名拨号或发短信")
        } else {
            appendLog("联系人权限被拒绝，仅可使用家人协助里配置的号码")
        }
    }

    fun onRecordAudioPermissionResult(application: Application, granted: Boolean) {
        initIfNeeded(application)
        wakeWordController?.onRecordAudioGranted(application, granted)
    }

    fun clearPermissionPrompt() {
        _state.update { it.updatePermissions { p -> p.copy(permissionPrompt = null) } }
    }

    private fun showPermissionPrompt(prompt: RuntimePermissionPrompt) {
        _state.update { it.updatePermissions { p -> p.copy(permissionPrompt = prompt) } }
    }

    private fun refreshMemories() {
        val summaries = memoryStore.loadRecentMemories().map { it.summary }
        _state.update { it.copy(recentMemories = summaries) }
        refreshSuggestionChips()
    }

    private fun refreshSuggestionChips() {
        _state.update { state ->
            state.copy(suggestionChips = SuggestionEngine.suggestions(state))
        }
    }

    private fun recordUserInteraction() {
        proactiveAssistantEngine.recordInteraction()
    }

    private fun restorePendingUiIfNeeded() {
        orchestrator.restorePendingFromDisk()
        val prompt = orchestrator.peekPendingPrompt() ?: return
        _state.update {
            it.copy(
                waitingForUserConfirm = true,
                confirmPrompt = prompt,
                needsBinaryConfirm = orchestrator.peekPendingNeedsBinaryConfirm(),
            )
        }
            when (orchestrator.peekPendingKind()) {
                PendingKind.INTENT_DISAMBIGUATION, PendingKind.NAV_POI_PICK -> {
                    val options = orchestrator.peekDisambiguationOptions()
                    upsertSessionCard(
                        ConversationCardFactory.disambiguation(
                            prompt,
                            options,
                        ),
                    )
                }
                PendingKind.LOCAL_PREVIEW -> {
                    upsertSessionCard(ConversationCardFactory.preview(prompt))
                }
                else -> {
                    upsertSessionCard(
                        ConversationCardFactory.confirm(
                            prompt,
                            orchestrator.peekPendingNeedsBinaryConfirm(),
                        ),
                    )
                }
            }
        publishConversationCards()
        syncOverlayVisibility()
    }

    private fun shouldShowOverlay(state: AgentUiState): Boolean {
        if (appInForeground) return false
        return state.isRunning ||
            state.isListening ||
            state.waitingForUserConfirm ||
            state.voiceInteractionState != VoiceInteractionState.Idle
    }

    fun isAppInForeground(): Boolean = appInForeground

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        syncOverlayVisibility()
        if (inForeground) {
            maybeDeliverProactiveNudge()
        }
    }

    private fun maybeDeliverProactiveNudge() {
        val app = application ?: return
        if (_state.value.isRunning || _state.value.isListening) return
        val nudge = proactiveAssistantEngine.peekNudge(_state.value.recentMemories) ?: return
        appendSessionCard(ConversationCardFactory.assistantMessage(nudge.spokenMessage))
        publishConversationCards()
        mainScope.launch {
            androidTtsOutput.speakAndAwait(nudge.spokenMessage, flush = true)
        }
        nudge.suggestionChip?.let { chip ->
            _state.update { state ->
                val merged = linkedSetOf<String>()
                merged += chip
                merged += state.suggestionChips
                state.copy(suggestionChips = merged.take(6))
            }
        }
    }

    fun clearInteraction() {
        val snapshot = _state.value
        when {
            snapshot.isListening ||
                snapshot.voiceInteractionState != VoiceInteractionState.Idle -> {
                // 叉叉 = 取消语音，不能用 stopVoiceInput（那会提交并可能直接跑 Agent）
                voiceSession()?.abortInput()
                voiceSession()?.sessionActive = false
                _state.update {
                    it.copy(
                        command = "",
                        speechText = "",
                        isListening = false,
                        voiceInteractionState = VoiceInteractionState.Idle,
                    )
                }
                wakeWordController?.ensureRunning()
                syncOverlayVisibility()
            }
            snapshot.waitingForUserConfirm -> clearPendingConfirmUI()
            else -> {
                _state.update { it.copy(command = "", speechText = "") }
                voiceSession()?.sessionActive = false
            }
        }
    }

    private fun syncOverlayVisibility() {
        val app = application ?: return
        if (!OverlayPermission.canDrawOverlays(app)) return
        if (visionOverlaySuppressionDepth > 0) {
            FloatingOverlayService.ensureStarted(app)
            FloatingOverlayService.hideDialog()
            return
        }
        if (shouldShowOverlay(_state.value)) {
            FloatingOverlayService.ensureStarted(app)
            FloatingOverlayService.showDialog()
        } else if (FloatingOverlayService.isRunning()) {
            FloatingOverlayService.hideDialog()
        }
    }

    /** 悬浮服务 onCreate 完成后回调，消化 ensureStarted/showDialog 竞态。 */
    fun syncOverlayVisibilityFromService() {
        syncOverlayVisibility()
    }

    suspend fun pushVisionOverlaySuppressionAwait(waitFrame: Boolean = false) {
        if (visionOverlaySuppressionDepth++ == 0) {
            val app = application
            if (app != null && OverlayPermission.canDrawOverlays(app)) {
                FloatingOverlayService.ensureStarted(app)
                FloatingOverlayService.hideDialogAwait(waitFrame = waitFrame)
            }
        }
    }

    fun popVisionOverlaySuppression() {
        if (visionOverlaySuppressionDepth <= 0) return
        if (--visionOverlaySuppressionDepth == 0) {
            syncOverlayVisibility()
        }
    }

    override suspend fun activateVisionAgentMode() {
        if (visionAgentActive) return
        visionAgentActive = true
        _state.update { it.copy(visionAgentActive = true) }
        publishConversationCards()
        syncOverlayVisibility()
    }

    override fun isVisionAgentActive(): Boolean = visionAgentActive

    override fun clearVisionAgentModeUi() {
        if (!visionAgentActive) return
        visionAgentActive = false
        _state.update { it.copy(visionAgentActive = false) }
        publishConversationCards()
        syncOverlayVisibility()
    }

    override suspend fun pushSuppressionAwait(waitFrame: Boolean) {
        pushVisionOverlaySuppressionAwait(waitFrame)
    }

    override fun popSuppression() {
        popVisionOverlaySuppression()
    }

    override fun deactivateVisionAgentMode() {
        visionAgentActive = false
        visionOverlaySuppressionDepth = 0
        _state.update { it.copy(visionAgentActive = false) }
        publishConversationCards()
        syncOverlayVisibility()
    }

    fun resetVisionOverlaySuppression() {
        if (visionOverlaySuppressionDepth == 0) return
        visionOverlaySuppressionDepth = 0
        syncOverlayVisibility()
    }

    private fun publishConversationCards() {
        _state.update { state ->
            val merged = state.copy(conversationCards = conversationCards.list())
            merged.copy(
                overlayInteractionCard = conversationCards.overlayInteractionCard(merged),
                overlaySessionCards = conversationCards.overlaySessionCards(merged),
            )
        }
    }

    private fun resetSessionCards(userCommand: String) {
        conversationCards.reset(userCommand)
    }

    private fun upsertSessionCard(card: ConversationCard) {
        conversationCards.upsert(card)
    }

    private fun appendSessionCard(card: ConversationCard) {
        conversationCards.append(card)
    }

    private fun isDuplicateCardContent(card: ConversationCard): Boolean =
        conversationCards.isDuplicate(card)

    private fun removeSessionCardsByKind(kind: ConversationCardKind) {
        conversationCards.removeByKind(kind)
    }

    private fun finalizeSessionCards(result: AgentRunResult) {
        removeSessionCardsByKind(ConversationCardKind.Progress)
        if (result.waitingForUserConfirm && !result.confirmPrompt.isNullOrBlank()) {
            when (orchestrator.peekPendingKind()) {
                PendingKind.INTENT_DISAMBIGUATION, PendingKind.NAV_POI_PICK -> {
                    val options = orchestrator.peekDisambiguationOptions()
                    upsertSessionCard(
                        ConversationCardFactory.disambiguation(
                            result.confirmPrompt.orEmpty(),
                            options,
                        ),
                    )
                }
                PendingKind.LOCAL_PREVIEW -> {
                    upsertSessionCard(ConversationCardFactory.preview(result.confirmPrompt.orEmpty()))
                }
                else -> {
                    upsertSessionCard(
                        ConversationCardFactory.confirm(
                            result.confirmPrompt.orEmpty(),
                            result.needsBinaryConfirm,
                        ),
                    )
                }
            }
        } else {
            removeSessionCardsByKind(ConversationCardKind.Confirm)
            removeSessionCardsByKind(ConversationCardKind.Disambiguation)
            removeSessionCardsByKind(ConversationCardKind.Preview)
            if (result.success && result.summary.isNotBlank()) {
                appendSessionCard(ConversationCardFactory.assistantMessage(result.summary))
                maybeAppendInfoCard(result.summary)
            }
            LocalUndoRegistry.peek()?.let {
                upsertSessionCard(ConversationCardFactory.undo("刚才的操作可以撤销，要撤销吗？"))
            }
        }
    }

    private fun maybeAppendInfoCard(message: String) {
        val text = message.trim()
        if (text.isBlank() || isDuplicateCardContent(ConversationCardFactory.info("信息", text))) return
        val looksLikeInfo = text.contains("天气") || text.contains("℃") ||
            text.contains("查询") || text.contains("读取") || text.contains("找到")
        if (looksLikeInfo) {
            appendSessionCard(ConversationCardFactory.info("信息", text))
        }
    }


    private suspend fun handleStandaloneAgentResult(application: Application, result: AgentRunResult) {
        if (result.waitingForUserConfirm) {
            visionAgentActive = false
            visionOverlaySuppressionDepth = 0
        }
        _state.update {
            it.copy(
                isRunning = false,
                waitingForUserConfirm = result.waitingForUserConfirm,
                confirmPrompt = result.confirmPrompt,
                needsBinaryConfirm = result.needsBinaryConfirm,
                statusMessage = result.summary,
                visionAgentActive = if (result.waitingForUserConfirm) false else it.visionAgentActive,
            )
        }
        finalizeSessionCards(result)
        publishConversationCards()
        syncOverlayVisibility()
        if (result.waitingForUserConfirm) {
            FloatingOverlayService.ensureStarted(application)
            FloatingOverlayService.showDialog()
        }
        val voice = voiceSession()
        val deferPromptToListen = result.waitingForUserConfirm &&
            orchestrator.peekPendingKind() in setOf(
                PendingKind.NAV_POI_PICK,
                PendingKind.INTENT_DISAMBIGUATION,
            )
        when {
            result.waitingForUserConfirm && voice?.sessionActive == true &&
                !result.confirmPrompt.isNullOrBlank() && !deferPromptToListen -> {
                voice.recordVoicePrompt(result.confirmPrompt)
                voice.applyBargeInPreRoll(voice.speakPromptWithOptionalBargeIn(result.confirmPrompt))
            }
            result.summary.isNotBlank() && !result.waitingForUserConfirm -> voice?.speakStatus(result.summary)
        }
        voice?.continueConversationAfterAgentResult(application, result)
    }

    fun setCloudContextConsent(application: Application, granted: Boolean) {
        initIfNeeded(application)
        if (granted) {
            contextConsentStore.grantConsent()
        } else {
            contextConsentStore.revokeConsent()
        }
        _state.update { it.copy(cloudContextConsentGranted = granted) }
        appendLog(
            if (granted) "已开启：允许云端理解屏幕内容" else "已关闭：云端屏幕理解",
        )
    }

    fun setVoiceBargeInEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        voiceInteractionConfigStore.setBargeInEnabled(enabled)
        _state.update { it.copy(voiceBargeInEnabled = enabled) }
        appendLog(if (enabled) "已开启：播报期间可直接说话打断" else "已关闭：需等播报结束再说话")
    }

    fun submitBinaryConfirm(approved: Boolean) {
        val app = application ?: return
        val text = if (approved) "确认" else "取消"
        voiceSession()?.sessionActive = true
        _state.update { it.copy(command = text, speechText = text) }
        runAgent(app, resumePendingConfirm = true)
    }

    fun selectDisambiguationOption(intentId: String) {
        val app = application ?: return
        recordUserInteraction()
        agentJob?.cancel()
        agentScope.launch {
            val result = when {
                NavPoiPickCodec.isNavPoiId(intentId) ||
                    orchestrator.peekPendingKind() == PendingKind.NAV_POI_PICK -> {
                    orchestrator.runNavPoiPick(
                        poiIntentId = intentId,
                        originalCommand = orchestrator.peekPendingOriginalCommand()
                            ?: _state.value.command,
                        appContext = app,
                        runContext = AgentRunContext(),
                    )
                }
                else -> {
                    orchestrator.runDisambiguatedIntent(
                        command = orchestrator.peekPendingOriginalCommand()
                            ?: _state.value.command,
                        intentId = intentId,
                        apiKey = _state.value.apiKey.ifBlank { apiKeyStore.getApiKey() },
                        appContext = app,
                        runContext = AgentRunContext(),
                    )
                }
            }
            handleStandaloneAgentResult(app, result)
        }
    }

    fun undoLastLocalAction() {
        val app = application ?: return
        val offer = LocalUndoRegistry.consume() ?: return
        removeSessionCardsByKind(ConversationCardKind.Undo)
        publishConversationCards()
        AccessibilityGateways.current?.performGlobalHome()
        voiceSession()?.speakStatus(offer.message)
        appendLog("用户撤销本地操作：${offer.action}")
    }

    fun dismissUndoOffer() {
        LocalUndoRegistry.clear()
        removeSessionCardsByKind(ConversationCardKind.Undo)
        publishConversationCards()
    }


    fun appendInfoCard(title: String, body: String) {
        appendSessionCard(ConversationCardFactory.info(title, body))
        publishConversationCards()
    }

    fun updateApiKey(value: String) {
        _state.update { it.copy(apiKey = value) }
    }

    fun saveApiKey(application: Application) {
        initIfNeeded(application)
        apiKeyStore.saveApiKey(_state.value.apiKey)
        appendLog("LLM API Key 已保存")
    }

    fun updateAsrApiKey(value: String) {
        _state.update { it.copy(asrApiKey = value) }
    }

    fun updateAsrAppId(value: String) {
        _state.update { it.copy(asrAppId = value) }
    }

    fun updateAsrAccessToken(value: String) {
        _state.update { it.copy(asrAccessToken = value) }
    }

    fun updateAsrResourceId(value: String) {
        _state.update { it.copy(asrResourceId = value) }
    }

    fun saveAsrConfig(application: Application) {
        initIfNeeded(application)
        val current = _state.value
        apiKeyStore.saveAsrConfig(
            apiKey = current.asrApiKey,
            appId = current.asrAppId,
            accessToken = current.asrAccessToken,
            resourceId = current.asrResourceId,
        )
        voiceSession()?.invalidateAsrClient()
        appendLog("豆包语音识别配置已保存")
    }

    fun updateWakeWordPhrase(value: String) {
        _state.update { it.updateWakeWord { w -> w.copy(phrase = value) } }
    }

    fun saveWakeWordConfig(application: Application) {
        initIfNeeded(application)
        wakeWordController?.saveConfig()
    }

    fun applyWakeWordPreset(application: Application, preset: WakeWordSensitivityPreset) {
        initIfNeeded(application)
        wakeWordController?.applyPreset(preset)
    }

    fun updateWakeWordKeywordScore(value: String) {
        val parsed = value.toFloatOrNull() ?: return
        _state.update { it.updateWakeWord { w -> w.copy(keywordScore = parsed.coerceIn(0.1f, 10f)) } }
    }

    fun updateWakeWordKeywordThreshold(value: String) {
        val parsed = value.toFloatOrNull() ?: return
        _state.update { it.updateWakeWord { w -> w.copy(keywordThreshold = parsed.coerceIn(0.01f, 5f)) } }
    }

    fun updateDaughterPhone(value: String) {
        _state.update { it.copy(daughterPhone = value) }
    }

    fun updateSonPhone(value: String) {
        _state.update { it.copy(sonPhone = value) }
    }

    fun updateEmergencyPhone(value: String) {
        _state.update { it.copy(emergencyPhone = value) }
    }

    fun updateEmergencyMessage(value: String) {
        _state.update { it.copy(emergencyMessage = value) }
    }

    fun updateHomeAddress(value: String) {
        _state.update { it.copy(homeAddress = value) }
    }

    fun updatePresetPhraseGoHome(value: String) {
        _state.update { it.copy(presetPhraseGoHome = value) }
    }

    fun saveCaregiverSettings(application: Application) {
        initIfNeeded(application)
        val current = _state.value
        val goHomeAliases = PresetTextNormalizer.splitAliases(current.presetPhraseGoHome)
        caregiverStore.saveFamilyContacts(
            listOf(
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "女儿", phoneNumber = current.daughterPhone.trim()),
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "儿子", phoneNumber = current.sonPhone.trim()),
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "紧急联系人", phoneNumber = current.emergencyPhone.trim()),
            ),
        )
        caregiverStore.saveEmergencyMessage(current.emergencyMessage)
        caregiverStore.saveHomeAddress(current.homeAddress)
        presetStore.savePresets(
            listOf(
                PresetCommand(
                    name = "回家导航",
                    aliases = if (goHomeAliases.isEmpty()) listOf("我要回家", "导航回家", "送我回家") else goHomeAliases,
                    action = "navigate_home",
                ),
            ),
        )
        appendLog("家人协助与预设指令已保存")
    }

    fun setWakeWordEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        wakeWordController?.setEnabled(application, enabled)
    }

    fun setWakeWordSileroVadEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        wakeWordController?.setSileroVadEnabled(enabled)
    }

    fun setWakeWordSecondStageEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        wakeWordController?.setSecondStageEnabled(enabled)
    }

    fun testWakeWord(application: Application) {
        initIfNeeded(application)
        wakeWordController?.testWakeWord(application)
    }

    fun startWakeWordCalibration(application: Application) {
        initIfNeeded(application)
        wakeWordController?.startCalibration(application)
    }

    fun recordCalibrationStep(application: Application) {
        initIfNeeded(application)
        wakeWordController?.recordCalibrationStep()
    }

    fun updateCommand(value: String) {
        _state.update { it.copy(command = value) }
    }

    fun cancelAgent() {
        runContext?.cancel()
        agentJob?.cancel()
        agentJob = null
        voiceSession()?.abortInput()
        voiceSession()?.resetConfirmReplyMode()
        voiceSession()?.sessionActive = false
        _state.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                isListening = false,
                voiceInteractionState = VoiceInteractionState.Idle,
                statusMessage = "已停止",
                command = "",
                speechText = "",
            )
        }
        appendLog("Agent 已停止")
        wakeWordController?.ensureRunning()
        syncOverlayVisibility()
    }

    fun pauseAgent() {
        if (!_state.value.isRunning || _state.value.isPaused) return
        runContext?.pause()
        _state.update { it.copy(isPaused = true, statusMessage = "已暂停") }
        appendLog("Agent 已暂停")
    }

    fun resumeAgent() {
        if (!_state.value.isRunning || !_state.value.isPaused) return
        runContext?.resume()
        _state.update { it.copy(isPaused = false, statusMessage = "继续执行") }
        appendLog("Agent 继续执行")
    }

    fun startVoiceInput() {
        application?.let { initIfNeeded(it) }
        if (_state.value.waitingForUserConfirm) {
            application?.let { startVoiceReplyToConfirm(it) }
            return
        }
        voiceSession()?.startVoiceInput()
    }

    fun resumeWakeWordVoiceSession() {
        val app = application ?: return
        initIfNeeded(app)
        voiceSession()?.resumeWakeWordVoiceSession()
    }

    fun startVoiceReplyToConfirm(application: Application) {
        initIfNeeded(application)
        voiceSession()?.startVoiceReplyToConfirm(application)
    }

    fun startVoiceOpenFollowUp(application: Application) {
        initIfNeeded(application)
        voiceSession()?.startVoiceOpenFollowUp(application)
    }

    fun stopVoiceInput() {
        voiceSession()?.stopVoiceInput()
    }

    fun stopVoiceInputAndRunAgent(application: Application) {
        initIfNeeded(application)
        voiceSession()?.stopVoiceInputAndRunAgent(application)
    }

    fun appendLog(message: String) {
        val safe = SafeLog.redact(message)
        SafeLog.i(safe)
        _state.update { it.copy(logs = (it.logs + safe).takeLast(100)) }
    }

    private fun hasRecordAudioPermission(application: Application): Boolean {
        return ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolvePresetCommand(command: String): String {
        val preset = presetStore.findByPhrase(command) ?: return command
        return when (preset.action) {
            "navigate_home" -> "导航回家"
            "navigate_to" -> "导航前往"
            else -> command
        }
    }

    fun runAgent(application: Application, resumePendingConfirm: Boolean? = null) {
        initIfNeeded(application)
        if (blocksLocalAgentForCaregiverAssist()) {
            appendLog("协助进行中：请在协作页远程操作，本地 Agent 已暂停")
            return
        }
        recordUserInteraction()
        val current = _state.value
        if (current.isRunning) {
            if (assistBridge?.remoteCommandRun == true) {
                relayRemoteAssistStatus(success = false, summary = "已有任务在执行")
            }
            return
        }
        val shouldResumePending = resumePendingConfirm == true
        val effectiveCommand = resolvePresetCommand(current.command)

        val context = AgentRunContext()
        runContext = context

        agentJob = agentScope.launch {
            if (!shouldResumePending) {
                resetSessionCards(effectiveCommand)
            } else {
                val reply = effectiveCommand.trim()
                if (reply.isNotBlank()) {
                    appendSessionCard(ConversationCardFactory.userCommand(reply))
                }
            }
            removeSessionCardsByKind(ConversationCardKind.Confirm)
            removeSessionCardsByKind(ConversationCardKind.Progress)
            // 新任务默认按无障碍交互展示；真正进入视觉兜底后再闩锁隐藏卡片
            clearVisionAgentModeUi()
            upsertSessionCard(ConversationCardFactory.progress("正在制定计划"))
            _state.update {
                it.copy(
                    isRunning = true,
                    isPaused = false,
                    waitingForUserConfirm = false,
                    confirmPrompt = null,
                    needsBinaryConfirm = false,
                    visionAgentActive = false,
                    overlayInteractionCard = null,
                    overlaySessionCards = emptyList(),
                    currentStep = 0,
                    statusMessage = "正在制定计划",
                    taskSteps = emptyList(),
                    taskPhases = emptyList(),
                )
            }
            publishConversationCards()
            syncOverlayVisibility()

            val apiKey = current.apiKey.ifBlank { apiKeyStore.getApiKey() }
            val initialPhases = if (shouldResumePending) {
                TaskPhasePlanner.planFromCommand(effectiveCommand)
            } else {
                appendLog("正在制定计划：$effectiveCommand")
                orchestrator.planUserFacingPhases(apiKey, effectiveCommand)
            }
            ConversationCardFactory.plan(initialPhases)?.let { upsertSessionCard(it) }
            upsertSessionCard(ConversationCardFactory.progress("启动中"))
            _state.update {
                it.copy(
                    statusMessage = "启动中",
                    taskPhases = initialPhases,
                )
            }
            publishConversationCards()
            syncOverlayVisibility()

            val startedAt = System.currentTimeMillis()
            if (effectiveCommand != current.command) {
                appendLog("预设指令命中：${current.command} -> $effectiveCommand")
            }
            appendLog("开始执行：$effectiveCommand")
            val voice = voiceSession()
            if (!shouldResumePending && voice?.sessionActive != true) {
                voice?.speakStatus("收到，正在执行")
            }

            try {
                val result = orchestrator.run(
                    userCommand = effectiveCommand,
                    apiKey = apiKey,
                    appContext = application,
                    runContext = context,
                    resumePendingConfirm = shouldResumePending,
                    onProgress = { step, message ->
                        val actionName = message.removePrefix("执行：")
                            .takeIf { message.startsWith("执行：") && it.isNotBlank() }
                        _state.update {
                            it.copy(
                                currentStep = step,
                                statusMessage = message,
                                sessionId = it.sessionId,
                                taskSteps = TaskStepTracker.buildProgressUpdate(
                                    it.taskSteps,
                                    step,
                                    message,
                                ),
                                taskPhases = TaskPhaseTracker.advanceFromAction(
                                    it.taskPhases,
                                    actionName,
                                ),
                            )
                        }
                        val snapshot = _state.value
                        ConversationCardFactory.plan(snapshot.taskPhases, snapshot.taskSteps)
                            ?.let { upsertSessionCard(it) }
                        upsertSessionCard(ConversationCardFactory.progress(snapshot.statusMessage))
                        maybeAppendInfoCard(message)
                        publishConversationCards()
                        syncOverlayVisibility()
                        if (assistBridge?.remoteCommandRun == true) {
                            assistBridge?.relayRunningStatus(message)
                        }
                    },
                )

                val elapsed = System.currentTimeMillis() - startedAt
                result.logs.forEach { step ->
                    appendLog(
                        "步骤${step.step} ${step.action.action} -> " +
                            "${if (step.success) "成功" else "失败"}：${step.detail}",
                    )
                }
                appendLog(
                    if (result.success) "完成（${elapsed}ms）：${result.summary}"
                    else "结束（${elapsed}ms）：${result.summary}",
                )
                // 先更新确认 UI，再播 TTS，避免弹窗等播报结束才出现
                refreshMemories()
                if (result.waitingForUserConfirm) {
                    // 确认态必须出卡：清视觉闩锁 + 临时藏窗深度，否则 sync 会继续 hideDialog
                    visionAgentActive = false
                    visionOverlaySuppressionDepth = 0
                }
                _state.update {
                    it.copy(
                        isRunning = false,
                        isPaused = false,
                        waitingForUserConfirm = result.waitingForUserConfirm,
                        confirmPrompt = result.confirmPrompt,
                        needsBinaryConfirm = result.needsBinaryConfirm,
                        sessionId = result.sessionId,
                        statusMessage = if (result.success) result.summary else result.summary,
                        visionAgentActive = if (result.waitingForUserConfirm) false else it.visionAgentActive,
                        // 非确认结束时清空输入，右侧按钮回到麦克风
                        command = if (result.waitingForUserConfirm) it.command else "",
                        speechText = if (result.waitingForUserConfirm) it.speechText else "",
                        taskSteps = if (result.success && !result.waitingForUserConfirm) {
                            TaskStepTracker.markAllCompleted(it.taskSteps)
                        } else {
                            it.taskSteps
                        },
                        taskPhases = if (result.success && !result.waitingForUserConfirm) {
                            TaskPhaseTracker.markAllCompleted(it.taskPhases)
                        } else {
                            it.taskPhases
                        },
                    )
                }
                finalizeSessionCards(result)
                publishConversationCards()
                syncOverlayVisibility()
                if (result.waitingForUserConfirm) {
                    // 再强制一次显卡，避免 ensureStarted 竞态导致 pending 未落到可见
                    FloatingOverlayService.ensureStarted(application)
                    FloatingOverlayService.showDialog()
                }
                refreshVisionDebugFrames()
                relayRemoteAssistStatus(result.success, result.summary)
                val deferPromptToListen = result.waitingForUserConfirm &&
                    orchestrator.peekPendingKind() in setOf(
                        PendingKind.NAV_POI_PICK,
                        PendingKind.INTENT_DISAMBIGUATION,
                    )
                when {
                    result.waitingForUserConfirm && voice?.sessionActive == true &&
                        !result.confirmPrompt.isNullOrBlank() && !deferPromptToListen -> {
                        voice.recordVoicePrompt(result.confirmPrompt)
                        voice.applyBargeInPreRoll(voice.speakPromptWithOptionalBargeIn(result.confirmPrompt))
                    }
                    result.waitingForUserConfirm -> Unit
                    voice?.sessionActive == true && result.summary.isNotBlank() -> {
                        voice.recordVoicePrompt(result.summary)
                        voice.applyBargeInPreRoll(voice.speakPromptWithOptionalBargeIn(result.summary))
                    }
                    result.summary.isNotBlank() -> voice?.speakStatus(result.summary, flush = true)
                }
                voice?.continueConversationAfterAgentResult(application, result)
            } catch (_: CancellationException) {
                relayRemoteAssistStatus(success = false, summary = "已停止")
                _state.update {
                    it.copy(isRunning = false, isPaused = false, statusMessage = "已停止")
                }
                syncOverlayVisibility()
                refreshVisionDebugFrames()
            } finally {
                agentJob = null
                runContext = null
                deactivateVisionAgentMode()
                voiceSession()?.scheduleWakeWordRestoreIfIdle()
            }
        }
    }

    fun clearPendingConfirmUI() {
        voiceSession()?.resetConfirmReplyMode()
        orchestrator.clearPendingUserReply()
        voiceSession()?.abortInput()
        _state.update { it.copy(waitingForUserConfirm = false, confirmPrompt = null, needsBinaryConfirm = false) }
        publishConversationCards()
        syncOverlayVisibility()
        voiceSession()?.scheduleWakeWordRestoreIfIdle()
    }

    fun onWakeWordDetected() {
        onWakeWordDetectedInternal(keyword = _state.value.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE })
    }

    fun onWakeWordDetectedInternal(keyword: String) {
        val app = application ?: return
        initIfNeeded(app)
        if (!_state.value.wakeWordEnabled) return
        if (_state.value.isRunning || _state.value.isListening) return
        if (!hasRecordAudioPermission(app)) {
            showPermissionPrompt(
                RuntimePermissionPrompt(
                    kind = RuntimePermissionKind.RecordAudio,
                    title = "需要麦克风权限",
                    message = "检测到唤醒词，但需要麦克风权限才能继续听您说话。",
                    resumeWakeWordVoice = true,
                ),
            )
            return
        }
        _state.update {
            it.updateWakeWord { w ->
                w.copy(
                    lastDetectedAtMs = System.currentTimeMillis(),
                    lastKeyword = keyword,
                    testHint = null,
                )
            }
        }
        appendLog("唤醒后继续听您说")
        voiceSession()?.startFromWakeWord(app)
    }

    fun setVisionDebugEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        visionDebugStore.setEnabled(enabled)
        _state.update { it.copy(visionDebugEnabled = enabled) }
        appendLog(if (enabled) "视觉调试已开启：Agent 将保存带坐标标记的截图" else "视觉调试已关闭")
    }

    fun setUiTreeLogcatEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        uiTreeLogcatStore.setEnabled(enabled)
        AccessibilityGateways.current?.setContinuousUiTreeLogcatEnabled(enabled)
        _state.update { it.copy(uiTreeLogcatEnabled = enabled) }
        appendLog(
            if (enabled) {
                "持续 UI 树 Logcat 已开启（tag=JoyForOld/UiTree，内容与「读取页面」相同）"
            } else {
                "持续 UI 树 Logcat 已关闭"
            },
        )
    }

    fun refreshVisionDebugFrames() {
        val frames = visionDebugStore.listFrames()
        _state.update { it.copy(visionDebugFrames = frames) }
    }

    fun clearVisionDebugFrames() {
        visionDebugStore.clearAll()
        _state.update { it.copy(visionDebugFrames = emptyList()) }
        appendLog("已清空视觉调试截图")
    }

    fun setAssistMode(active: Boolean) {
        _state.update { it.updateAssist { a -> a.copy(mode = active) } }
    }

    fun isAssistModeActive(): Boolean = assistBridge?.isAssistModeActive() == true

    fun refreshAssistConfig() {
        val app = application ?: return
        initIfNeeded(app)
        assistBridge?.refreshConfig()
    }

    fun setAssistRole(role: AssistRole) {
        ensureAssistBridge()?.setRole(role)
    }

    fun setAssistDisplayName(name: String) {
        ensureAssistBridge()?.setDisplayName(name)
    }

    fun setAssistServerHttpUrl(url: String) {
        ensureAssistBridge()?.setServerHttpUrl(url)
    }

    fun setAssistServerWsUrl(url: String) {
        ensureAssistBridge()?.setServerWsUrl(url)
    }

    fun startElderAssistSession() {
        ensureAssistBridge()?.startElderSession()
        assistBridge?.requestNavigation()
    }

    fun joinAssistSession(pairCode: String) {
        ensureAssistBridge()?.joinWithPairCode(pairCode)
    }

    fun connectAssistBinding(binding: BindingDto) {
        ensureAssistBridge()?.connectBinding(binding)
    }

    fun deleteAssistBinding(bindingId: String) {
        ensureAssistBridge()?.deleteBinding(bindingId)
    }

    fun sendAssistTap(x: Int, y: Int) {
        ensureAssistBridge()?.sendTap(x, y)
    }

    fun sendAssistSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        ensureAssistBridge()?.sendSwipe(x1, y1, x2, y2)
    }

    fun sendAssistAction(name: String) {
        ensureAssistBridge()?.sendAction(name)
    }

    fun sendAssistTypeText(text: String) {
        ensureAssistBridge()?.sendTypeText(text)
    }

    fun sendAssistCommand(text: String) {
        ensureAssistBridge()?.sendCommand(text)
    }

    fun endAssistSession() {
        ensureAssistBridge()?.endSession()
    }

    private fun ensureAssistBridge(): AssistRuntimeBridge? {
        val app = application ?: return null
        initIfNeeded(app)
        return assistBridge
    }

    fun submitRemoteAssistCommand(application: Application, command: String) {
        initIfNeeded(application)
        assistBridge?.remoteCommandRun = true
        updateCommand(command)
        runAgent(application)
    }

    private fun blocksLocalAgentForCaregiverAssist(): Boolean =
        assistBridge?.blocksLocalAgent() == true

    private fun relayRemoteAssistStatus(success: Boolean, summary: String) {
        assistBridge?.relayAgentStatus(success, summary)
    }

    fun previewPageTree() {
        val service = AccessibilityGateways.current
        if (service == null) {
            appendLog("无法读取页面：无障碍服务未开启")
            return
        }
        appendLog("---- 当前前台应用页面 ----")
        service.snapshotForAgent().lines().forEach { appendLog(it) }
    }
}
