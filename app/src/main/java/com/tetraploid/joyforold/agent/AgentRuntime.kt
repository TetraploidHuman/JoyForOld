package com.tetraploid.joyforold.agent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.accessibility.AccessibilityPermission
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.OverlayPermission
import com.tetraploid.joyforold.offline.nlu.OfflineNluModelManager
import com.tetraploid.joyforold.preset.PresetCommand
import com.tetraploid.joyforold.preset.PresetCommandStore
import com.tetraploid.joyforold.preset.PresetTextNormalizer
import com.tetraploid.joyforold.privacy.SafeLog
import com.tetraploid.joyforold.speech.DoubaoAsrClient
import com.tetraploid.joyforold.speech.AndroidTtsOutput
import com.tetraploid.joyforold.speech.DoubaoSpeechInput
import com.tetraploid.joyforold.speech.SpeechEchoFilter
import com.tetraploid.joyforold.speech.VoiceTurnCoordinator
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.system.ContactResolver
import com.tetraploid.joyforold.system.NotificationAccessPermission
import com.tetraploid.joyforold.util.NetworkStatus
import com.tetraploid.joyforold.wakeword.SherpaOnnxModelManager
import com.tetraploid.joyforold.wakeword.SileroVadModelManager
import com.tetraploid.joyforold.wakeword.WakeWordCalibrationSession
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import com.tetraploid.joyforold.wakeword.WakeWordService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
)

data class AgentUiState(
    val apiKey: String = "",
    val modelName: String = "",
    val asrApiKey: String = "",
    val asrAppId: String = "",
    val asrAccessToken: String = "",
    val asrResourceId: String = "",
    val command: String = "",
    val speechText: String = "",
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val isListening: Boolean = false,
    val voiceInteractionState: VoiceInteractionState = VoiceInteractionState.Idle,
    val isPaused: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityServiceConnected: Boolean = false,
    val recordAudioGranted: Boolean = false,
    val readContactsGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val waitingForUserConfirm: Boolean = false,
    val confirmPrompt: String? = null,
    val needsBinaryConfirm: Boolean = false,
    val currentStep: Int = 0,
    val statusMessage: String = "",
    val taskSteps: List<TaskStepItem> = emptyList(),
    val taskPhases: List<TaskPhaseItem> = emptyList(),
    val conversationCards: List<ConversationCard> = emptyList(),
    val overlayInteractionCard: ConversationCard? = null,
    val sessionId: String? = null,
    val recentMemories: List<String> = emptyList(),
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = "",
    val wakeWordRunning: Boolean = false,
    val lastWakeWordAtMs: Long? = null,
    val lastWakeWordKeyword: String? = null,
    val wakeWordTestHint: String? = null,
    val wakeWordKeywordScore: Float = WakeWordConfigStore.DEFAULT_KEYWORD_SCORE,
    val wakeWordKeywordThreshold: Float = WakeWordConfigStore.DEFAULT_KEYWORD_THRESHOLD,
    val wakeWordConfirmHits: Int = WakeWordConfigStore.DEFAULT_CONFIRM_HITS,
    val wakeWordPreset: WakeWordSensitivityPreset = WakeWordSensitivityPreset.BALANCED,
    val wakeWordModelVersion: String = SherpaOnnxModelManager.MODEL_VERSION,
    val wakeWordCalibrationRunning: Boolean = false,
    val wakeWordCalibrationStep: Int = 0,
    val wakeWordCalibrationHint: String? = null,
    val wakeWordCalibrated: Boolean = false,
    val wakeWordSileroVadEnabled: Boolean = WakeWordConfigStore.DEFAULT_SILERO_VAD,
    val wakeWordSecondStageEnabled: Boolean = WakeWordConfigStore.DEFAULT_SECOND_STAGE,
    val daughterPhone: String = "",
    val sonPhone: String = "",
    val emergencyPhone: String = "",
    val emergencyMessage: String = "",
    val homeAddress: String = "",
    val presetPhraseGoHome: String = "我要回家, 导航回家, 送我回家",
    val permissionPrompt: RuntimePermissionPrompt? = null,
)

object AgentRuntime {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val orchestrator = AgentOrchestrator()
    private var apiKeyStore: ApiKeyStore? = null
    private var wakeWordStore: WakeWordConfigStore? = null
    private var memoryStore: AgentMemoryStore? = null
    private var sessionStore: AgentSessionStore? = null
    private var asrClient: DoubaoAsrClient? = null
    private var speechInput: DoubaoSpeechInput? = null
    private var androidTtsOutput: AndroidTtsOutput? = null
    private var voiceTurnCoordinator: VoiceTurnCoordinator? = null
    private var appHintStore: AppHintStore? = null
    private var voiceConfirmReplyMode = false
    private var voiceReplyApplication: Application? = null
    private var agentJob: Job? = null
    private var runContext: AgentRunContext? = null
    private var application: Application? = null
    private var calibrationSession: WakeWordCalibrationSession? = null
    private var calibrationJob: Job? = null
    private var cachedAsrParams: AsrParams? = null
    private var caregiverStore: CaregiverSupportStore? = null
    private var presetStore: PresetCommandStore? = null

    @Volatile
    private var appInForeground: Boolean = false

    private var accessibilityStateListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    @Volatile
    private var voiceSessionActive = false

    private val recentVoicePrompts = ArrayDeque<String>(6)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun initIfNeeded(application: Application) {
        this.application = application.applicationContext as Application
        if (apiKeyStore == null) {
            apiKeyStore = ApiKeyStore(application)
            wakeWordStore = WakeWordConfigStore(application)
            memoryStore = AgentMemoryStore(application).also { orchestrator.bindMemoryStore(it) }
            sessionStore = AgentSessionStore(application).also { orchestrator.bindSessionStore(it) }
            appHintStore = AppHintStore(application).also {
                it.ensureSeededDefaults()
                orchestrator.bindAppHintStore(it)
            }
            androidTtsOutput = AndroidTtsOutput(application).also { it.ensureReady() }
            caregiverStore = CaregiverSupportStore(application).also { it.ensureSeededDefaults() }
            presetStore = PresetCommandStore(application).also {
                it.ensureSeededDefaults()
                orchestrator.bindPresetStore(it)
            }
            refreshMemories()
            restorePendingUiIfNeeded()
            mainScope.launch(Dispatchers.IO) {
                OfflineNluModelManager.getClassifier(application)
            }
            _state.update {
                val contacts = caregiverStore!!.loadFamilyContacts()
                val daughter = contacts.firstOrNull { it.alias == "女儿" }
                val son = contacts.firstOrNull { it.alias == "儿子" }
                val emergency = contacts.firstOrNull { it.alias == "紧急联系人" }
                val goHomePreset = presetStore!!.loadPresets().firstOrNull { it.action == "navigate_home" }
                it.copy(
                    apiKey = apiKeyStore!!.getApiKey(),
                    modelName = apiKeyStore!!.getModel(),
                    asrApiKey = apiKeyStore!!.getAsrApiKey(),
                    asrAppId = apiKeyStore!!.getAsrAppId(),
                    asrAccessToken = apiKeyStore!!.getAsrAccessToken(),
                    asrResourceId = apiKeyStore!!.getAsrResourceId(),
                    wakeWordEnabled = wakeWordStore!!.isEnabled(),
                    wakeWordPhrase = wakeWordStore!!.getPhrase(),
                    wakeWordRunning = wakeWordStore!!.isEnabled() && WakeWordService.isRunning,
                    wakeWordKeywordScore = wakeWordStore!!.getKeywordScore(),
                    wakeWordKeywordThreshold = wakeWordStore!!.getKeywordThreshold(),
                    wakeWordConfirmHits = wakeWordStore!!.getConfirmHitCount(),
                    wakeWordPreset = wakeWordStore!!.getPreset(),
                    wakeWordModelVersion = SherpaOnnxModelManager.MODEL_VERSION,
                    wakeWordCalibrated = wakeWordStore!!.isCalibrated(),
                    wakeWordSileroVadEnabled = wakeWordStore!!.isSileroVadEnabled(),
                    wakeWordSecondStageEnabled = wakeWordStore!!.isSecondStageEnabled(),
                    daughterPhone = daughter?.phoneNumber.orEmpty(),
                    sonPhone = son?.phoneNumber.orEmpty(),
                    emergencyPhone = emergency?.phoneNumber.orEmpty(),
                    emergencyMessage = caregiverStore!!.loadEmergencyMessage(),
                    homeAddress = caregiverStore!!.loadHomeAddress(),
                    presetPhraseGoHome = goHomePreset?.aliases?.joinToString(", ") ?: "我要回家, 导航回家, 送我回家",
                    recordAudioGranted = hasRecordAudioPermission(application),
                    readContactsGranted = ContactResolver.hasContactsPermission(application),
                    notificationAccessGranted = NotificationAccessPermission.isEnabled(application),
                )
            }
            preloadWakeWordModelIfNeeded()
            migrateWakeWordDefaultsIfNeeded()
            migrateWakeWordAntiFalsePositiveIfNeeded()
            migrateWakeWordQualityIfNeeded()
            syncWakeWordService()
        }
        ensureAccessibilityStateListener(application)
        refreshAccessibilityState()
    }

    fun refreshAccessibilityState() {
        val app = application
        val settingEnabled = app?.let { AccessibilityPermission.isSettingEnabled(it) }
            ?: AccessibilityPermission.isServiceConnected()
        val connected = AccessibilityPermission.isServiceConnected()
        _state.update {
            it.copy(
                accessibilityEnabled = settingEnabled,
                accessibilityServiceConnected = connected,
                wakeWordRunning = it.wakeWordEnabled && WakeWordService.isRunning,
                recordAudioGranted = app?.let { ctx -> hasRecordAudioPermission(ctx) } ?: it.recordAudioGranted,
                readContactsGranted = app?.let { ctx -> ContactResolver.hasContactsPermission(ctx) } ?: it.readContactsGranted,
                notificationAccessGranted = app?.let { ctx ->
                    NotificationAccessPermission.isEnabled(ctx)
                } ?: it.notificationAccessGranted,
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
        _state.update { it.copy(readContactsGranted = granted) }
        if (granted) {
            appendLog("联系人权限已授予，可按姓名拨号或发短信")
        } else {
            appendLog("联系人权限被拒绝，仅可使用家人协助里配置的号码")
        }
    }

    fun onRecordAudioPermissionResult(application: Application, granted: Boolean) {
        initIfNeeded(application)
        _state.update { it.copy(recordAudioGranted = granted) }
        if (granted) {
            appendLog("麦克风权限已授予")
            syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
        } else {
            appendLog("麦克风权限被拒绝，语音识别与本地唤醒不可用")
            WakeWordService.stop(application)
            _state.update { it.copy(wakeWordRunning = false) }
        }
    }

    fun clearPermissionPrompt() {
        _state.update { it.copy(permissionPrompt = null) }
    }

    private fun showPermissionPrompt(prompt: RuntimePermissionPrompt) {
        _state.update { it.copy(permissionPrompt = prompt) }
    }

    private fun refreshMemories() {
        val summaries = memoryStore?.loadRecentMemories()?.map { it.summary }.orEmpty()
        _state.update { it.copy(recentMemories = summaries) }
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
    }

    fun clearInteraction() {
        when {
            _state.value.isListening -> stopVoiceInput()
            _state.value.waitingForUserConfirm -> clearPendingConfirmUI()
            else -> _state.update { it.copy(command = "", speechText = "") }
        }
        voiceSessionActive = false
    }

    private fun syncOverlayVisibility() {
        val app = application ?: return
        if (!OverlayPermission.canDrawOverlays(app)) return
        if (shouldShowOverlay(_state.value)) {
            FloatingOverlayService.ensureStarted(app)
            FloatingOverlayService.showDialog()
        } else if (FloatingOverlayService.isRunning()) {
            FloatingOverlayService.hideDialog()
        }
    }

    private val sessionCards = mutableListOf<ConversationCard>()

    private fun publishConversationCards() {
        _state.update { state ->
            val merged = state.copy(conversationCards = sessionCards.toList())
            merged.copy(overlayInteractionCard = ConversationCardFactory.overlayInteraction(merged))
        }
    }

    private fun resetSessionCards(userCommand: String) {
        sessionCards.clear()
        val trimmed = userCommand.trim()
        if (trimmed.isNotBlank()) {
            sessionCards += ConversationCardFactory.userCommand(trimmed)
        }
    }

    private fun upsertSessionCard(card: ConversationCard) {
        val index = sessionCards.indexOfFirst { it.kind == card.kind && it.id == card.id }
        if (index >= 0) {
            sessionCards[index] = card
        } else {
            val kindIndex = sessionCards.indexOfFirst { it.kind == card.kind }
            if (kindIndex >= 0 && card.kind in setOf(
                    ConversationCardKind.Plan,
                    ConversationCardKind.Progress,
                    ConversationCardKind.Confirm,
                )
            ) {
                sessionCards[kindIndex] = card
            } else {
                sessionCards += card
            }
        }
    }

    private fun appendSessionCard(card: ConversationCard) {
        if (isDuplicateCardContent(card)) return
        sessionCards += card
    }

    private fun isDuplicateCardContent(card: ConversationCard): Boolean {
        val body = card.body.trim()
        if (body.isNotBlank()) {
            if (sessionCards.any { it.body.trim() == body }) return true
        }
        if (card.kind == ConversationCardKind.Assistant && body.isNotBlank()) {
            if (sessionCards.any {
                    (it.kind == ConversationCardKind.Confirm || it.kind == ConversationCardKind.User) &&
                        it.body.trim() == body
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun removeSessionCardsByKind(kind: ConversationCardKind) {
        sessionCards.removeAll { it.kind == kind }
    }

    private fun finalizeSessionCards(result: AgentRunResult) {
        removeSessionCardsByKind(ConversationCardKind.Progress)
        if (result.waitingForUserConfirm && !result.confirmPrompt.isNullOrBlank()) {
            upsertSessionCard(
                ConversationCardFactory.confirm(
                    result.confirmPrompt.orEmpty(),
                    result.needsBinaryConfirm,
                ),
            )
        } else {
            removeSessionCardsByKind(ConversationCardKind.Confirm)
            if (result.success && result.summary.isNotBlank()) {
                appendSessionCard(ConversationCardFactory.assistantMessage(result.summary))
                maybeAppendInfoCard(result.summary)
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

    private fun shouldContinueConversation(summary: String): Boolean {
        val text = summary.trim()
        if (text.isEmpty()) return false
        return text.contains('?') || text.contains('？') ||
            text.contains("有什么可以帮") || text.contains("请说") ||
            text.contains("请告诉") || text.contains("还有什么")
    }

    private fun continueVoiceConversation(application: Application, result: AgentRunResult) {
        if (!voiceSessionActive) return
        mainScope.launch {
            androidTtsOutput?.awaitIdle()
            when {
                result.waitingForUserConfirm && result.needsBinaryConfirm ->
                    startVoiceReplyToConfirm(application)
                result.waitingForUserConfirm && !result.needsBinaryConfirm ->
                    startVoiceOpenFollowUp(application)
                !result.waitingForUserConfirm && result.success && shouldContinueConversation(result.summary) ->
                    startVoiceInputInternal(
                        confirmReplyMode = false,
                        application = application,
                        skipPrompt = true,
                    )
            }
        }
    }

    fun submitBinaryConfirm(approved: Boolean) {
        val app = application ?: return
        val text = if (approved) "发送" else "取消"
        voiceSessionActive = true
        _state.update { it.copy(command = text, speechText = text) }
        runAgent(app, resumePendingConfirm = true)
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
        apiKeyStore?.saveApiKey(_state.value.apiKey)
        appendLog("DeepSeek API Key 已保存")
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
        apiKeyStore?.saveAsrConfig(
            apiKey = current.asrApiKey,
            appId = current.asrAppId,
            accessToken = current.asrAccessToken,
            resourceId = current.asrResourceId,
        )
        asrClient = null
        speechInput = null
        cachedAsrParams = null
        appendLog("豆包语音识别配置已保存")
    }

    fun updateWakeWordPhrase(value: String) {
        _state.update { it.copy(wakeWordPhrase = value) }
    }

    fun saveWakeWordConfig(application: Application) {
        initIfNeeded(application)
        val current = _state.value
        val phrase = current.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        val score = current.wakeWordKeywordScore
        val threshold = current.wakeWordKeywordThreshold
        val confirmHits = current.wakeWordConfirmHits
        wakeWordStore?.savePhrase(phrase)
        wakeWordStore?.saveKeywordScore(score)
        wakeWordStore?.saveKeywordThreshold(threshold)
        wakeWordStore?.saveConfirmHitCount(confirmHits)
        wakeWordStore?.savePreset(current.wakeWordPreset)
        _state.update {
            it.copy(
                wakeWordPhrase = phrase,
                wakeWordKeywordScore = score,
                wakeWordKeywordThreshold = threshold,
                wakeWordConfirmHits = confirmHits,
            )
        }
        syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
        appendLog(
            "唤醒配置已保存：$phrase，score=$score，threshold=$threshold，confirm=$confirmHits，" +
                "预设=${current.wakeWordPreset.label}",
        )
    }

    fun applyWakeWordPreset(application: Application, preset: WakeWordSensitivityPreset) {
        initIfNeeded(application)
        wakeWordStore?.applyPreset(preset)
        _state.update {
            it.copy(
                wakeWordPreset = preset,
                wakeWordKeywordScore = preset.keywordScore,
                wakeWordKeywordThreshold = preset.keywordThreshold,
                wakeWordConfirmHits = preset.confirmHits,
            )
        }
        syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
        appendLog(
            "已切换唤醒预设「${preset.label}」：score=${preset.keywordScore}，" +
                "threshold=${preset.keywordThreshold}，二次确认=${preset.confirmHits}次",
        )
    }

    fun updateWakeWordKeywordScore(value: String) {
        val parsed = value.toFloatOrNull() ?: return
        _state.update { it.copy(wakeWordKeywordScore = parsed.coerceIn(0.1f, 10f)) }
    }

    fun updateWakeWordKeywordThreshold(value: String) {
        val parsed = value.toFloatOrNull() ?: return
        _state.update { it.copy(wakeWordKeywordThreshold = parsed.coerceIn(0.01f, 5f)) }
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
        caregiverStore?.saveFamilyContacts(
            listOf(
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "女儿", phoneNumber = current.daughterPhone.trim()),
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "儿子", phoneNumber = current.sonPhone.trim()),
                com.tetraploid.joyforold.caregiver.FamilyContact(alias = "紧急联系人", phoneNumber = current.emergencyPhone.trim()),
            ),
        )
        caregiverStore?.saveEmergencyMessage(current.emergencyMessage)
        caregiverStore?.saveHomeAddress(current.homeAddress)
        presetStore?.savePresets(
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
        if (enabled && !hasRecordAudioPermission(application)) {
            showPermissionPrompt(
                RuntimePermissionPrompt(
                    kind = RuntimePermissionKind.RecordAudio,
                    title = "需要麦克风权限",
                    message = "开启语音唤醒需要麦克风权限，用于聆听唤醒词和您的指令。",
                    resumeEnableWakeWord = true,
                ),
            )
            return
        }
        wakeWordStore?.saveEnabled(enabled)
        _state.update { it.copy(wakeWordEnabled = enabled, wakeWordRunning = enabled) }
        syncWakeWordService()
        appendLog(if (enabled) "本地语音唤醒已开启" else "本地语音唤醒已关闭")
    }

    fun setWakeWordSileroVadEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        wakeWordStore?.saveSileroVadEnabled(enabled)
        _state.update { it.copy(wakeWordSileroVadEnabled = enabled) }
        syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
        appendLog(if (enabled) "Silero VAD 已开启" else "Silero VAD 已关闭（回退 RMS）")
    }

    fun setWakeWordSecondStageEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
        wakeWordStore?.saveSecondStageEnabled(enabled)
        _state.update { it.copy(wakeWordSecondStageEnabled = enabled) }
        syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
        appendLog(if (enabled) "二阶段唤醒已开启" else "二阶段唤醒已关闭")
    }

    fun testWakeWord(application: Application) {
        initIfNeeded(application)
        if (!hasRecordAudioPermission(application)) {
            showPermissionPrompt(
                RuntimePermissionPrompt(
                    kind = RuntimePermissionKind.RecordAudio,
                    title = "需要麦克风权限",
                    message = "测试唤醒词需要麦克风权限。",
                ),
            )
            return
        }
        val phrase = _state.value.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        if (!_state.value.wakeWordEnabled) {
            wakeWordStore?.saveEnabled(true)
            _state.update { it.copy(wakeWordEnabled = true, wakeWordRunning = true) }
        }
        _state.update {
            it.copy(
                lastWakeWordAtMs = null,
                lastWakeWordKeyword = null,
                wakeWordTestHint = "请说唤醒词：$phrase",
            )
        }
        syncWakeWordService()
        appendLog("开始测试唤醒词：请说「$phrase」")
    }

    fun startWakeWordCalibration(application: Application) {
        initIfNeeded(application)
        if (!hasRecordAudioPermission(application)) {
            appendLog("无法开始唤醒标定：请先授予麦克风权限")
            return
        }
        calibrationJob?.cancel()
        calibrationSession?.release()
        val phrase = _state.value.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        val score = _state.value.wakeWordKeywordScore
        val threshold = _state.value.wakeWordKeywordThreshold
        pauseWakeWordForMicSharing()
        calibrationSession = WakeWordCalibrationSession(application, phrase, score, threshold)
        _state.update {
            it.copy(
                wakeWordCalibrationRunning = true,
                wakeWordCalibrationStep = 0,
                wakeWordCalibrationHint = "标定步骤 1/4：请清晰说出「$phrase」后点「录制样本」",
            )
        }
        calibrationJob = mainScope.launch(Dispatchers.IO) {
            val ready = calibrationSession?.prepare() == true
            if (!ready) {
                appendLog("唤醒标定初始化失败，请检查模型是否已下载")
                finishCalibration(resetOnly = true)
            } else {
                appendLog("唤醒标定已开始：需录制 3 次唤醒样本 + 1 次环境音")
            }
        }
    }

    fun recordCalibrationStep(application: Application) {
        initIfNeeded(application)
        val session = calibrationSession ?: return
        if (!_state.value.wakeWordCalibrationRunning) return
        val step = _state.value.wakeWordCalibrationStep
        calibrationJob?.cancel()
        calibrationJob = mainScope.launch(Dispatchers.IO) {
            val phrase = _state.value.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
            when {
                step < WakeWordCalibrationSession.POSITIVE_TARGET -> {
                    appendLog("正在录制唤醒样本 ${step + 1}/${WakeWordCalibrationSession.POSITIVE_TARGET}…")
                    val ok = session.recordPositiveSample()
                    if (!ok) {
                        appendLog("录制失败，请检查麦克风权限")
                        return@launch
                    }
                    val next = step + 1
                    _state.update {
                        it.copy(
                            wakeWordCalibrationStep = next,
                            wakeWordCalibrationHint = if (next < WakeWordCalibrationSession.POSITIVE_TARGET) {
                                "标定步骤 ${next + 1}/4：再说一次「$phrase」"
                            } else {
                                "标定步骤 4/4：保持安静 5 秒，点「录制环境音」"
                            },
                        )
                    }
                    appendLog("已保存唤醒样本 $next/${WakeWordCalibrationSession.POSITIVE_TARGET}")
                }
                step == WakeWordCalibrationSession.POSITIVE_TARGET -> {
                    appendLog("正在录制环境音（约 5 秒）…")
                    val ok = session.recordNegativeSample()
                    if (!ok) {
                        appendLog("环境音录制失败")
                        return@launch
                    }
                    val result = session.calibrate()
                    if (result == null) {
                        appendLog("标定失败：样本不足或无法命中，请重试")
                        finishCalibration(resetOnly = true)
                        return@launch
                    }
                    wakeWordStore?.saveKeywordThreshold(result.recommendedThreshold)
                    wakeWordStore?.saveKeywordScore(result.recommendedScore)
                    wakeWordStore?.saveCalibrated(true)
                    _state.update {
                        it.copy(
                            wakeWordKeywordThreshold = result.recommendedThreshold,
                            wakeWordKeywordScore = result.recommendedScore,
                            wakeWordCalibrated = true,
                            wakeWordCalibrationStep = WakeWordCalibrationSession.POSITIVE_TARGET + 1,
                            wakeWordCalibrationHint =
                                "标定完成：threshold=${result.recommendedThreshold}，" +
                                    "正样本命中率=${"%.0f".format(result.positiveHitRate * 100)}%，" +
                                    "环境误触=${"%.0f".format(result.negativeHitRate * 100)}%",
                        )
                    }
                    appendLog(
                        "唤醒标定完成：threshold=${result.recommendedThreshold}，" +
                            "正样本 ${result.positiveHitRate}，环境误触 ${result.negativeHitRate}",
                    )
                    finishCalibration(resetOnly = false)
                    syncWakeWordService(forceRestart = _state.value.wakeWordEnabled)
                }
                else -> finishCalibration(resetOnly = false)
            }
        }
    }

    private fun finishCalibration(resetOnly: Boolean) {
        calibrationJob?.cancel()
        calibrationSession?.release()
        calibrationSession = null
        if (resetOnly) {
            _state.update {
                it.copy(
                    wakeWordCalibrationRunning = false,
                    wakeWordCalibrationStep = 0,
                    wakeWordCalibrationHint = null,
                )
            }
            ensureWakeWordServiceRunning()
        } else {
            _state.update { it.copy(wakeWordCalibrationRunning = false) }
        }
    }

    fun updateCommand(value: String) {
        _state.update { it.copy(command = value) }
    }

    fun cancelAgent() {
        runContext?.cancel()
        agentJob?.cancel()
        agentJob = null
        if (_state.value.isListening) {
            speechInput?.let { input ->
                mainScope.launch { input.stop { } }
            }
        }
        voiceTurnCoordinator?.cancelVoice()
        voiceConfirmReplyMode = false
        voiceReplyApplication = null
        voiceSessionActive = false
        _state.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                isListening = false,
                voiceInteractionState = VoiceInteractionState.Idle,
                statusMessage = "已停止",
            )
        }
        appendLog("Agent 已停止")
        ensureWakeWordServiceRunning()
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
        voiceSessionActive = true
        startVoiceInputInternal(confirmReplyMode = false, application = null)
    }

    fun resumeWakeWordVoiceSession() {
        val app = application ?: return
        initIfNeeded(app)
        if (_state.value.isRunning || _state.value.isListening) return
        voiceSessionActive = true
        mainScope.launch(Dispatchers.Main.immediate) {
            startVoiceInputInternal(
                confirmReplyMode = false,
                application = app,
                wakeWordActivation = true,
            )
        }
    }

    fun startVoiceReplyToConfirm(application: Application) {
        if (!_state.value.waitingForUserConfirm) return
        if (_state.value.isListening || _state.value.isRunning) return
        initIfNeeded(application)
        voiceSessionActive = true
        mainScope.launch {
            androidTtsOutput?.awaitIdle()
            startVoiceInputInternal(confirmReplyMode = true, application = application)
        }
    }

    fun startVoiceOpenFollowUp(application: Application) {
        if (!_state.value.waitingForUserConfirm) return
        if (_state.value.isListening || _state.value.isRunning) return
        initIfNeeded(application)
        voiceSessionActive = true
        mainScope.launch {
            androidTtsOutput?.awaitIdle()
            startVoiceInputInternal(
                confirmReplyMode = true,
                application = application,
                skipPrompt = false,
            )
        }
    }

    private fun startVoiceInputInternal(
        confirmReplyMode: Boolean,
        application: Application?,
        skipPrompt: Boolean = false,
        wakeWordActivation: Boolean = false,
    ) {
        if (_state.value.isListening) return
        val app = application ?: this.application
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
        if (_state.value.wakeWordEnabled) {
            pauseWakeWordForMicSharing()
        }
        voiceConfirmReplyMode = confirmReplyMode
        voiceReplyApplication = application
        val prompt = when {
            skipPrompt -> null
            wakeWordActivation -> "在呢，请说"
            confirmReplyMode -> _state.value.confirmPrompt ?: "请回答确认问题"
            else -> "请说出您的指令"
        }
        appendLog(if (confirmReplyMode) "请先听完问题，再语音回答" else "开始语音识别")
        prompt?.let { recordVoicePrompt(it) }
        mainScope.launch {
            _state.update {
                it.copy(
                    isListening = false,
                    speechText = "",
                    voiceInteractionState = if (prompt.isNullOrBlank()) {
                        VoiceInteractionState.Listening
                    } else {
                        VoiceInteractionState.SpeakingPrompt
                    },
                )
            }
            syncOverlayVisibility()
            coordinator.speakPromptThenListen(
                prompt = prompt,
                session = SpeechInputSession(
                    shortUtterance = confirmReplyMode,
                    onPartialText = { text ->
                        if (text.isBlank()) return@SpeechInputSession
                        val cleaned = filterVoiceRecognition(text)
                        if (cleaned.isBlank()) return@SpeechInputSession
                        mainScope.launch(Dispatchers.Main.immediate) {
                            _state.update { it.copy(speechText = cleaned, command = cleaned) }
                        }
                    },
                    onFinalText = { text ->
                        mainScope.launch {
                            voiceTurnCoordinator?.markProcessing()
                            _state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing) }
                            handleVoiceFinalText(text, fromAutoStop = true)
                            voiceTurnCoordinator?.markIdle()
                        }
                    },
                    onError = { error ->
                        mainScope.launch {
                            val recovered = _state.value.speechText.trim()
                            if (recovered.isNotBlank()) {
                                appendLog("语音识别收尾异常，已使用识别结果：$recovered")
                                voiceTurnCoordinator?.markProcessing()
                                _state.update {
                                    it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing)
                                }
                                handleVoiceFinalText(recovered, fromAutoStop = true)
                                voiceTurnCoordinator?.markIdle()
                                return@launch
                            }
                            appendLog(error)
                            speakStatus("语音识别失败，请重试")
                            voiceConfirmReplyMode = false
                            voiceReplyApplication = null
                            _state.update {
                                it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle)
                            }
                            ensureWakeWordServiceRunning()
                            syncOverlayVisibility()
                        }
                    },
                ),
            )
        }
    }

    fun stopVoiceInput() {
        val input = speechInput ?: run {
            _state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle) }
            syncOverlayVisibility()
            return
        }
        mainScope.launch {
            input.stop { finalText ->
                voiceTurnCoordinator?.markProcessing()
                _state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing) }
                handleVoiceFinalText(finalText, fromAutoStop = false)
                voiceTurnCoordinator?.markIdle()
                syncOverlayVisibility()
            }
        }
    }

    fun stopVoiceInputAndRunAgent(application: Application) {
        voiceSessionActive = true
        val input = speechInput ?: run {
            _state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Idle) }
            syncOverlayVisibility()
            return
        }
        mainScope.launch {
            input.stop { finalText ->
                voiceTurnCoordinator?.markProcessing()
                _state.update { it.copy(isListening = false, voiceInteractionState = VoiceInteractionState.Processing) }
                handleVoiceFinalText(finalText, fromAutoStop = false, forceRun = true, application = application)
                voiceTurnCoordinator?.markIdle()
                syncOverlayVisibility()
            }
        }
    }

    private fun handleVoiceFinalText(
        text: String,
        fromAutoStop: Boolean,
        forceRun: Boolean = false,
        application: Application? = null,
    ) {
        val raw = text.ifBlank { _state.value.speechText }
        val merged = filterVoiceRecognition(raw)
        _state.update { it.copy(isListening = false, speechText = merged, command = merged) }

        val app = application ?: voiceReplyApplication
        val isConfirmReply = voiceConfirmReplyMode
        voiceConfirmReplyMode = false
        voiceReplyApplication = null

        appendLog(
            if (merged.isBlank()) "语音识别结束：未识别到文本" else "语音识别：$merged",
        )

        if (merged.isBlank()) {
            if (isConfirmReply && app != null && _state.value.waitingForUserConfirm) {
                appendLog("未听清，请再说一次")
                mainScope.launch {
                    voiceTurnCoordinator?.speakResult("没有听清，请再说一次")
                    startVoiceReplyToConfirm(app)
                }
            } else {
                ensureWakeWordServiceRunning()
            }
            return
        }

        if (isConfirmReply && _state.value.waitingForUserConfirm) {
            val pendingKind = orchestrator.peekPendingKind()
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
                PendingKind.ROUTE_CLARIFY -> when (VoiceConfirmPhraseMatcher.classify(merged)) {
                    VoiceConfirmPhraseMatcher.Intent.CANCEL -> {
                        appendLog("用户取消：$merged")
                        speakStatus("好的，已取消")
                        clearPendingConfirmUI()
                        ensureWakeWordServiceRunning()
                        return
                    }
                    VoiceConfirmPhraseMatcher.Intent.CONFIRM -> appendLog("用户确认：$merged")
                    VoiceConfirmPhraseMatcher.Intent.UNCLEAR -> {
                        if (VoiceFollowUpDetector.looksLikeNewCommand(merged)) {
                            appendLog("识别为新指令，结束路由确认：$merged")
                            orchestrator.clearPendingUserReply()
                            _state.update {
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
                PendingKind.USER_CONFIRM -> {
                    val needsBinary = orchestrator.peekPendingNeedsBinaryConfirm()
                    val intent = VoiceConfirmPhraseMatcher.classify(merged)

                    if (intent == VoiceConfirmPhraseMatcher.Intent.CANCEL) {
                        appendLog("用户取消：$merged")
                        speakStatus("好的，已取消")
                        clearPendingConfirmUI()
                        ensureWakeWordServiceRunning()
                        return
                    }

                    if (!needsBinary) {
                        if (VoiceFollowUpDetector.looksLikeNewCommand(merged)) {
                            appendLog("识别为新指令，结束等待：$merged")
                            orchestrator.clearPendingUserReply()
                            _state.update {
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
                                    orchestrator.clearPendingUserReply()
                                    _state.update {
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

        if (app == null) {
            ensureWakeWordServiceRunning()
            return
        }
        appendLog("继续执行：$merged")
        val resumePending = isConfirmReply && _state.value.waitingForUserConfirm
        runAgent(app, resumePendingConfirm = resumePending)
    }

    fun appendLog(message: String) {
        val safe = SafeLog.redact(message)
        SafeLog.i(safe)
        _state.update { it.copy(logs = (it.logs + safe).takeLast(100)) }
    }

    private fun speakStatus(text: String, flush: Boolean = false) {
        val concise = text.trim().take(120)
        if (concise.isBlank()) return
        recordVoicePrompt(concise)
        androidTtsOutput?.speak(concise, flush = flush)
    }

    private fun recordVoicePrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        recentVoicePrompts.removeAll { it == trimmed }
        recentVoicePrompts.addLast(trimmed)
        while (recentVoicePrompts.size > 6) {
            recentVoicePrompts.removeFirst()
        }
    }

    private fun filterVoiceRecognition(text: String): String {
        return SpeechEchoFilter.stripEcho(text, recentVoicePrompts.toList())
    }

    private fun ensureVoiceStack(): VoiceTurnCoordinator? {
        val client = ensureAsrClient() ?: return null
        val input = DoubaoSpeechInput(client)
        speechInput = input
        val tts = androidTtsOutput ?: return null
        val coordinator = VoiceTurnCoordinator(
            ttsOutput = tts,
            speechInput = input,
            onStateChanged = { state ->
                _state.update {
                    it.copy(
                        voiceInteractionState = state,
                        isListening = state == VoiceInteractionState.Listening,
                    )
                }
                syncOverlayVisibility()
            },
            awaitTtsIdle = { tts.awaitIdle() },
        )
        voiceTurnCoordinator = coordinator
        return coordinator
    }

    private fun hasRecordAudioPermission(application: Application): Boolean {
        return ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolvePresetCommand(command: String): String {
        val preset = presetStore?.findByPhrase(command) ?: return command
        return when (preset.action) {
            "navigate_home" -> "导航回家"
            else -> command
        }
    }

    fun runAgent(application: Application, resumePendingConfirm: Boolean? = null) {
        initIfNeeded(application)
        val current = _state.value
        if (current.isRunning) return
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
            val initialPhases = TaskPhasePlanner.planFromCommand(effectiveCommand)
            ConversationCardFactory.plan(initialPhases)?.let { upsertSessionCard(it) }
            _state.update {
                it.copy(
                    isRunning = true,
                    isPaused = false,
                    waitingForUserConfirm = false,
                    confirmPrompt = null,
                    needsBinaryConfirm = false,
                    overlayInteractionCard = null,
                    currentStep = 0,
                    statusMessage = "启动中",
                    taskSteps = emptyList(),
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
            if (!shouldResumePending && !voiceSessionActive) {
                speakStatus("收到，正在执行")
            }

            try {
                val result = orchestrator.run(
                    userCommand = effectiveCommand,
                    apiKey = current.apiKey.ifBlank { apiKeyStore?.getApiKey().orEmpty() },
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
                when {
                    result.waitingForUserConfirm -> Unit
                    voiceSessionActive && result.summary.isNotBlank() -> {
                        recordVoicePrompt(result.summary)
                        androidTtsOutput?.speakAndAwait(result.summary, flush = true)
                    }
                    result.summary.isNotBlank() -> speakStatus(result.summary, flush = true)
                }
                refreshMemories()
                _state.update {
                    it.copy(
                        isRunning = false,
                        isPaused = false,
                        waitingForUserConfirm = result.waitingForUserConfirm,
                        confirmPrompt = result.confirmPrompt,
                        needsBinaryConfirm = result.needsBinaryConfirm,
                        sessionId = result.sessionId,
                        statusMessage = if (result.success) result.summary else result.summary,
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
                continueVoiceConversation(application, result)
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(isRunning = false, isPaused = false, statusMessage = "已停止")
                }
                syncOverlayVisibility()
            } finally {
                agentJob = null
                runContext = null
                ensureWakeWordServiceRunning()
            }
        }
    }

    fun clearPendingConfirmUI() {
        voiceConfirmReplyMode = false
        voiceReplyApplication = null
        voiceTurnCoordinator?.cancelVoice()
        orchestrator.clearPendingUserReply()
        if (_state.value.isListening) stopVoiceInput()
        _state.update { it.copy(waitingForUserConfirm = false, confirmPrompt = null, needsBinaryConfirm = false) }
        publishConversationCards()
        syncOverlayVisibility()
    }

    private fun syncWakeWordService(forceRestart: Boolean = false) {
        val app = application ?: return
        val enabled = _state.value.wakeWordEnabled
        if (!enabled) {
            WakeWordService.stop(app)
            _state.update { it.copy(wakeWordRunning = false) }
            return
        }
        if (!hasRecordAudioPermission(app)) {
            WakeWordService.stop(app)
            _state.update { it.copy(wakeWordRunning = false, wakeWordEnabled = false) }
            wakeWordStore?.saveEnabled(false)
            appendLog("本地唤醒未启动：缺少麦克风权限")
            return
        }
        if (forceRestart) {
            WakeWordService.stop(app)
            WakeWordService.start(app)
        } else if (!WakeWordService.isRunning) {
            WakeWordService.start(app)
        }
        _state.update { it.copy(wakeWordRunning = WakeWordService.isRunning) }
    }

    private fun pauseWakeWordForMicSharing() {
        val app = application ?: return
        if (!_state.value.wakeWordEnabled) return
        WakeWordService.stop(app)
        _state.update { it.copy(wakeWordRunning = false) }
    }

    private fun ensureWakeWordServiceRunning() {
        syncWakeWordService(forceRestart = false)
    }

    private fun migrateWakeWordDefaultsIfNeeded() {
        val store = wakeWordStore ?: return
        if (!store.needsRecallMigration()) return
        val preset = store.getPreset()
        var migrated = false
        when {
            preset == WakeWordSensitivityPreset.BALANCED &&
                (
                    (store.getKeywordScore() == 3.2f && store.getKeywordThreshold() == 0.011f) ||
                        (store.getKeywordScore() == 3.0f && store.getKeywordThreshold() == 0.015f)
                    ) -> {
                store.applyPreset(WakeWordSensitivityPreset.BALANCED)
                migrated = true
            }
            preset == WakeWordSensitivityPreset.SENSITIVE &&
                store.getKeywordScore() == 3.5f &&
                store.getKeywordThreshold() == 0.010f -> {
                store.applyPreset(WakeWordSensitivityPreset.SENSITIVE)
                migrated = true
            }
        }
        if (store.isSecondStageEnabled()) {
            store.saveSecondStageEnabled(false)
            migrated = true
        }
        if (preset != WakeWordSensitivityPreset.STRICT && store.isVadGateEnabled()) {
            store.saveVadGateEnabled(false)
            migrated = true
        }
        store.markRecallMigrationDone()
        if (!migrated) return
        _state.update {
            it.copy(
                wakeWordKeywordScore = store.getKeywordScore(),
                wakeWordKeywordThreshold = store.getKeywordThreshold(),
                wakeWordConfirmHits = store.getConfirmHitCount(),
                wakeWordPreset = store.getPreset(),
                wakeWordSileroVadEnabled = store.isSileroVadEnabled(),
                wakeWordSecondStageEnabled = store.isSecondStageEnabled(),
            )
        }
        appendLog("已自动优化唤醒灵敏度配置（提升召回率）")
    }

    private fun migrateWakeWordAntiFalsePositiveIfNeeded() {
        val store = wakeWordStore ?: return
        if (!store.needsAntiFalsePositiveMigration()) return
        val threshold = store.getKeywordThreshold()
        val confirmHits = store.getConfirmHitCount()
        val score = store.getKeywordScore()
        val tooSensitive = threshold <= 0.012f || confirmHits <= 1 || score >= 3.5f
        if (tooSensitive && store.getPreset() != WakeWordSensitivityPreset.SENSITIVE) {
            store.applyPreset(WakeWordSensitivityPreset.BALANCED)
            store.saveSecondStageEnabled(true)
            _state.update {
                it.copy(
                    wakeWordKeywordScore = store.getKeywordScore(),
                    wakeWordKeywordThreshold = store.getKeywordThreshold(),
                    wakeWordConfirmHits = store.getConfirmHitCount(),
                    wakeWordPreset = store.getPreset(),
                    wakeWordSecondStageEnabled = store.isSecondStageEnabled(),
                )
            }
            appendLog("已收紧唤醒灵敏度，降低误触（二次确认 + 二阶段复检）")
        }
        store.markAntiFalsePositiveMigrationDone()
    }

    private fun migrateWakeWordQualityIfNeeded() {
        val store = wakeWordStore ?: return
        if (!store.needsWakeQualityMigration()) return
        val preset = store.getPreset()
        if (preset != WakeWordSensitivityPreset.SENSITIVE || store.getConfirmHitCount() <= 1) {
            store.applyPreset(WakeWordSensitivityPreset.BALANCED)
        }
        store.saveVadGateEnabled(true)
        store.saveSecondStageEnabled(true)
        store.saveSileroVadEnabled(true)
        _state.update {
            it.copy(
                wakeWordKeywordScore = store.getKeywordScore(),
                wakeWordKeywordThreshold = store.getKeywordThreshold(),
                wakeWordConfirmHits = store.getConfirmHitCount(),
                wakeWordPreset = store.getPreset(),
                wakeWordSileroVadEnabled = store.isSileroVadEnabled(),
                wakeWordSecondStageEnabled = store.isSecondStageEnabled(),
            )
        }
        store.markWakeQualityMigrationDone()
        appendLog("已优化唤醒：启用语音活动门控 + 二次确认，降低环境噪音误触")
    }

    private fun preloadWakeWordModelIfNeeded() {
        if (!BuildConfig.DEBUG) return
        val app = application ?: return
        mainScope.launch(Dispatchers.IO) {
            appendLog("开发模式：开始预下载本地唤醒模型（${SherpaOnnxModelManager.MODEL_VERSION}）")
            val kwsOk = SherpaOnnxModelManager(app).preloadModelIfNeeded()
            val vadOk = runCatching { SileroVadModelManager(app).ensureReady(); true }.getOrDefault(false)
            appendLog(
                when {
                    kwsOk && vadOk -> "本地唤醒模型与 Silero VAD 预下载完成"
                    kwsOk -> "KWS 模型已就绪，Silero VAD 预下载失败"
                    else -> "本地唤醒模型预下载失败，请检查网络后重试"
                },
            )
        }
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
            it.copy(
                lastWakeWordAtMs = System.currentTimeMillis(),
                lastWakeWordKeyword = keyword,
                wakeWordTestHint = null,
            )
        }
        appendLog("检测到唤醒词，开始语音指令识别")
        voiceSessionActive = true
        mainScope.launch(Dispatchers.Main.immediate) {
            startVoiceInputInternal(
                confirmReplyMode = false,
                application = app,
                wakeWordActivation = true,
            )
        }
    }

    fun previewPageTree() {
        val service = JoyAccessibilityService.instance
        if (service == null) {
            appendLog("无法读取页面：无障碍服务未开启")
            return
        }
        appendLog("---- 当前前台应用页面 ----")
        service.snapshotForAgent().lines().forEach { appendLog(it) }
    }

    private fun ensureAsrClient(): DoubaoAsrClient? {
        val params = resolveAsrParams() ?: return null
        if (asrClient != null && cachedAsrParams == params) return asrClient
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
        val current = _state.value
        val store = apiKeyStore
        val apiKey = current.asrApiKey.ifBlank { store?.getAsrApiKey().orEmpty() }.orEmpty()
        val appId = current.asrAppId.ifBlank { store?.getAsrAppId().orEmpty() }.orEmpty()
        val accessToken = current.asrAccessToken.ifBlank { store?.getAsrAccessToken().orEmpty() }.orEmpty()
        val resourceId = current.asrResourceId.ifBlank { store?.getAsrResourceId().orEmpty() }.orEmpty()
        val hasNewApiKey = apiKey.isNotBlank()
        val hasLegacyAuth = appId.isNotBlank() && accessToken.isNotBlank()
        if (!hasNewApiKey && !hasLegacyAuth) return null
        return AsrParams(apiKey, appId, accessToken, resourceId)
    }

    private data class AsrParams(
        val apiKey: String,
        val appId: String,
        val accessToken: String,
        val resourceId: String,
    )
}
