package com.tetraploid.joyforold.agent

import android.app.Application
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.overlay.VoiceConfirmOverlayService
import com.tetraploid.joyforold.preset.PresetCommand
import com.tetraploid.joyforold.preset.PresetCommandStore
import com.tetraploid.joyforold.speech.DoubaoAsrClient
import com.tetraploid.joyforold.speech.JoyTtsSpeaker
import com.tetraploid.joyforold.wakeword.SherpaOnnxModelManager
import com.tetraploid.joyforold.wakeword.SileroVadModelManager
import com.tetraploid.joyforold.wakeword.WakeWordCalibrationSession
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import com.tetraploid.joyforold.wakeword.WakeWordService
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
    val isPaused: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val waitingForUserConfirm: Boolean = false,
    val confirmPrompt: String? = null,
    val currentStep: Int = 0,
    val statusMessage: String = "",
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
    val presetPhraseGoHome: String = "我要回家",
)

object AgentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val orchestrator = AgentOrchestrator()
    private var apiKeyStore: ApiKeyStore? = null
    private var wakeWordStore: WakeWordConfigStore? = null
    private var memoryStore: AgentMemoryStore? = null
    private var sessionStore: AgentSessionStore? = null
    private var asrClient: DoubaoAsrClient? = null
    private var cachedAsrParams: AsrParams? = null
    private var voiceConfirmReplyMode = false
    private var voiceReplyApplication: Application? = null
    private var agentJob: Job? = null
    private var runContext: AgentRunContext? = null
    private var application: Application? = null
    private var calibrationSession: WakeWordCalibrationSession? = null
    private var calibrationJob: Job? = null
    private var ttsSpeaker: JoyTtsSpeaker? = null
    private var caregiverStore: CaregiverSupportStore? = null
    private var presetStore: PresetCommandStore? = null

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun initIfNeeded(application: Application) {
        this.application = application.applicationContext as Application
        if (apiKeyStore == null) {
            apiKeyStore = ApiKeyStore(application)
            wakeWordStore = WakeWordConfigStore(application)
            memoryStore = AgentMemoryStore(application).also { orchestrator.bindMemoryStore(it) }
            sessionStore = AgentSessionStore(application).also { orchestrator.bindSessionStore(it) }
            ttsSpeaker = JoyTtsSpeaker(application).also { it.ensureReady() }
            caregiverStore = CaregiverSupportStore(application).also { it.ensureSeededDefaults() }
            presetStore = PresetCommandStore(application).also { it.ensureSeededDefaults() }
            refreshMemories()
            restorePendingUiIfNeeded()
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
                    presetPhraseGoHome = goHomePreset?.phrase ?: "我要回家",
                )
            }
            preloadWakeWordModelIfNeeded()
            migrateWakeWordDefaultsIfNeeded()
            syncWakeWordService()
        }
    }

    fun refreshAccessibilityState() {
        _state.update {
            it.copy(
                accessibilityEnabled = JoyAccessibilityService.instance != null,
                wakeWordRunning = it.wakeWordEnabled && WakeWordService.isRunning,
            )
        }
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
            )
        }
        syncConfirmOverlay()
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
                    phrase = current.presetPhraseGoHome.trim().ifBlank { "我要回家" },
                    action = "navigate_home",
                ),
            ),
        )
        appendLog("家人协助与预设指令已保存")
    }

    fun setWakeWordEnabled(application: Application, enabled: Boolean) {
        initIfNeeded(application)
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
        calibrationJob = scope.launch(Dispatchers.IO) {
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
        calibrationJob = scope.launch(Dispatchers.IO) {
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
            asrClient?.let { client ->
                scope.launch { client.stop { } }
            }
        }
        voiceConfirmReplyMode = false
        voiceReplyApplication = null
        _state.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                statusMessage = "已停止",
            )
        }
        appendLog("Agent 已停止")
        ensureWakeWordServiceRunning()
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
        startVoiceInputInternal(confirmReplyMode = false, application = null)
    }

    fun startVoiceReplyToConfirm(application: Application) {
        if (!_state.value.waitingForUserConfirm) return
        if (_state.value.isListening || _state.value.isRunning) return
        initIfNeeded(application)
        startVoiceInputInternal(confirmReplyMode = true, application = application)
    }

    private fun startVoiceInputInternal(confirmReplyMode: Boolean, application: Application?) {
        if (_state.value.isListening) return
        val client = ensureAsrClient()
        if (client == null) {
            appendLog("语音识别未配置：请在下方填写豆包 ASR 配置，或写入 local.properties")
            return
        }
        if (_state.value.wakeWordEnabled) {
            pauseWakeWordForMicSharing()
        }
        voiceConfirmReplyMode = confirmReplyMode
        voiceReplyApplication = application
        _state.update { it.copy(isListening = true, speechText = "") }
        appendLog(if (confirmReplyMode) "请用语音回答..." else "开始语音识别...")
        speakStatus(if (confirmReplyMode) "请回答确认问题" else "开始听您说话")
        client.start(
            onPartialText = { text ->
                if (text.isBlank()) return@start
                scope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(speechText = text, command = text) }
                }
            },
            onFinalText = { text ->
                scope.launch {
                    handleVoiceFinalText(text, fromAutoStop = true)
                }
            },
            onError = { error ->
                scope.launch {
                    appendLog(error)
                    speakStatus("语音识别失败，请重试")
                    voiceConfirmReplyMode = false
                    voiceReplyApplication = null
                    _state.update { it.copy(isListening = false) }
                    ensureWakeWordServiceRunning()
                }
            },
            shortUtterance = confirmReplyMode,
        )
    }

    fun stopVoiceInput() {
        val client = asrClient ?: run {
            _state.update { it.copy(isListening = false) }
            return
        }
        scope.launch {
            client.stop { finalText ->
                handleVoiceFinalText(finalText, fromAutoStop = false)
            }
        }
    }

    fun stopVoiceInputAndRunAgent(application: Application) {
        val client = asrClient ?: run {
            _state.update { it.copy(isListening = false) }
            return
        }
        scope.launch {
            client.stop { finalText ->
                handleVoiceFinalText(finalText, fromAutoStop = false, forceRun = true, application = application)
            }
        }
    }

    private fun handleVoiceFinalText(
        text: String,
        fromAutoStop: Boolean,
        forceRun: Boolean = false,
        application: Application? = null,
    ) {
        val merged = text.ifBlank { _state.value.speechText }
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
                speakStatus("没有听清，请再说一次")
                startVoiceReplyToConfirm(app)
            } else {
                ensureWakeWordServiceRunning()
            }
            return
        }

        if (app == null) {
            ensureWakeWordServiceRunning()
            return
        }
        // 用户要求：无论是否由唤醒触发，只要识别到命令就默认执行。
        // confirmReplyMode 仍然会走“继续执行”分支，但这里统一执行即可。
        appendLog("继续执行：$merged")
        speakStatus("好的，开始为您执行")
        val resumePending = isConfirmReply && _state.value.waitingForUserConfirm
        runAgent(app, resumePendingConfirm = resumePending)
    }

    fun appendLog(message: String) {
        _state.update { it.copy(logs = (it.logs + message).takeLast(100)) }
    }

    private fun speakStatus(text: String, flush: Boolean = false) {
        val concise = text.trim().take(120)
        if (concise.isBlank()) return
        ttsSpeaker?.speak(concise, flush = flush)
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
        val shouldResumePending = resumePendingConfirm ?: current.waitingForUserConfirm
        val effectiveCommand = resolvePresetCommand(current.command)

        val context = AgentRunContext()
        runContext = context

        agentJob = scope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    isPaused = false,
                    waitingForUserConfirm = false,
                    confirmPrompt = null,
                    currentStep = 0,
                    statusMessage = "启动中",
                )
            }
            syncConfirmOverlay()
            FloatingOverlayService.collapsePanel()
            val startedAt = System.currentTimeMillis()
            if (effectiveCommand != current.command) {
                appendLog("预设指令命中：${current.command} -> $effectiveCommand")
            }
            appendLog("开始执行：$effectiveCommand")
            speakStatus("收到，正在执行")

            try {
                val result = orchestrator.run(
                    userCommand = effectiveCommand,
                    apiKey = current.apiKey.ifBlank { apiKeyStore?.getApiKey().orEmpty() },
                    runContext = context,
                    resumePendingConfirm = shouldResumePending,
                    onProgress = { step, message ->
                        _state.update {
                            it.copy(currentStep = step, statusMessage = message, sessionId = it.sessionId)
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
                speakStatus(
                    if (result.waitingForUserConfirm) {
                        result.confirmPrompt.orEmpty()
                    } else {
                        result.summary
                    },
                    flush = true,
                )
                refreshMemories()
                _state.update {
                    it.copy(
                        isRunning = false,
                        isPaused = false,
                        waitingForUserConfirm = result.waitingForUserConfirm,
                        confirmPrompt = result.confirmPrompt,
                        sessionId = result.sessionId,
                        statusMessage = if (result.success) "完成" else result.summary,
                    )
                }
                syncConfirmOverlay()
                if (result.waitingForUserConfirm) {
                    startVoiceReplyToConfirm(application)
                }
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(isRunning = false, isPaused = false, statusMessage = "已停止")
                }
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
        orchestrator.clearPendingUserReply()
        if (_state.value.isListening) stopVoiceInput()
        _state.update { it.copy(waitingForUserConfirm = false, confirmPrompt = null) }
        syncConfirmOverlay()
    }

    private fun syncWakeWordService(forceRestart: Boolean = false) {
        val app = application ?: return
        val enabled = _state.value.wakeWordEnabled
        if (!enabled) {
            WakeWordService.stop(app)
            _state.update { it.copy(wakeWordRunning = false) }
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
        val preset = store.getPreset()
        if (preset != WakeWordSensitivityPreset.BALANCED) return
        val isLegacyBalanced =
            store.getKeywordScore() == 3.0f &&
                store.getKeywordThreshold() == 0.015f &&
                store.getConfirmHitCount() == 2
        if (!isLegacyBalanced) return
        store.applyPreset(WakeWordSensitivityPreset.BALANCED)
        _state.update {
            it.copy(
                wakeWordKeywordScore = WakeWordSensitivityPreset.BALANCED.keywordScore,
                wakeWordKeywordThreshold = WakeWordSensitivityPreset.BALANCED.keywordThreshold,
                wakeWordConfirmHits = WakeWordSensitivityPreset.BALANCED.confirmHits,
            )
        }
    }

    private fun preloadWakeWordModelIfNeeded() {
        if (!BuildConfig.DEBUG) return
        val app = application ?: return
        scope.launch(Dispatchers.IO) {
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
        if (!_state.value.wakeWordEnabled) return
        if (_state.value.isRunning || _state.value.isListening) return
        _state.update {
            it.copy(
                lastWakeWordAtMs = System.currentTimeMillis(),
                lastWakeWordKeyword = keyword,
                wakeWordTestHint = null,
            )
        }
        appendLog("检测到唤醒词，开始语音指令识别")
        scope.launch(Dispatchers.Main.immediate) {
            startVoiceInputInternal(confirmReplyMode = false, application = app)
        }
    }

    private fun syncConfirmOverlay() {
        val app = application ?: return
        val current = _state.value
        VoiceConfirmOverlayService.sync(
            app,
            waiting = current.waitingForUserConfirm,
            prompt = current.confirmPrompt,
        )
    }

    fun previewPageTree() {
        val service = JoyAccessibilityService.instance
        if (service == null) {
            appendLog("无法读取页面：无障碍服务未开启")
            return
        }
        FloatingOverlayService.collapsePanel()
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
