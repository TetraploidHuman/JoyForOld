package com.tetraploid.joyforold.agent.runtime

import android.app.Application
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.agent.RuntimePermissionKind
import com.tetraploid.joyforold.agent.RuntimePermissionPrompt
import com.tetraploid.joyforold.agent.updatePermissions
import com.tetraploid.joyforold.agent.updateWakeWord
import com.tetraploid.joyforold.speech.AsrSpeakerProfileStore
import com.tetraploid.joyforold.wakeword.SherpaOnnxModelManager
import com.tetraploid.joyforold.wakeword.SileroVadModelManager
import com.tetraploid.joyforold.wakeword.WakeWordCalibrationSession
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import com.tetraploid.joyforold.wakeword.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本地唤醒词：服务生命周期、标定、配置迁移与麦克风互斥。
 */
internal class WakeWordController(
    private val mainScope: CoroutineScope,
    private val state: AgentStateAccessor,
    private val appendLog: (String) -> Unit,
    private val hasRecordAudioPermission: (Application) -> Boolean,
    private val requestRecordAudioForWakeWord: (RuntimePermissionPrompt) -> Unit,
    private var applicationProvider: () -> Application?,
    private var storeProvider: () -> WakeWordConfigStore?,
    private var speakerProfileProvider: () -> AsrSpeakerProfileStore?,
) {
    private var calibrationSession: WakeWordCalibrationSession? = null
    private var calibrationJob: Job? = null

    fun bind(
        applicationProvider: () -> Application?,
        storeProvider: () -> WakeWordConfigStore?,
        speakerProfileProvider: () -> AsrSpeakerProfileStore?,
    ) {
        this.applicationProvider = applicationProvider
        this.storeProvider = storeProvider
        this.speakerProfileProvider = speakerProfileProvider
    }

    private val wakeWordStore: WakeWordConfigStore?
        get() = storeProvider()

    fun syncService(forceRestart: Boolean = false) {
        val app = applicationProvider() ?: return
        val enabled = state.read().wakeWordEnabled
        if (!enabled) {
            WakeWordService.stop(app)
            state.update { it.updateWakeWord { w -> w.copy(running = false) } }
            return
        }
        if (!hasRecordAudioPermission(app)) {
            WakeWordService.stop(app)
            state.update { it.updateWakeWord { w -> w.copy(running = false, enabled = false) } }
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
        state.update { it.updateWakeWord { w -> w.copy(running = WakeWordService.isRunning) } }
    }

    fun pauseForMicSharing() {
        val app = applicationProvider() ?: return
        if (!state.read().wakeWordEnabled) return
        WakeWordService.stop(app)
        state.update { it.updateWakeWord { w -> w.copy(running = false) } }
    }

    suspend fun awaitMicReleased() {
        var waits = 0
        while ((WakeWordService.isRunning || !WakeWordService.micReleased) && waits < 30) {
            delay(50)
            waits++
        }
    }

    fun ensureRunning() {
        syncService(forceRestart = false)
    }

    fun setEnabled(application: Application, enabled: Boolean) {
        if (enabled && !hasRecordAudioPermission(application)) {
            requestRecordAudioForWakeWord(
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
        state.update { it.updateWakeWord { w -> w.copy(enabled = enabled, running = enabled) } }
        syncService()
        appendLog(if (enabled) "本地语音唤醒已开启" else "本地语音唤醒已关闭")
    }

    fun saveConfig() {
        val current = state.read()
        val phrase = current.wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        wakeWordStore?.savePhrase(phrase)
        wakeWordStore?.saveKeywordScore(current.wakeWordKeywordScore)
        wakeWordStore?.saveKeywordThreshold(current.wakeWordKeywordThreshold)
        wakeWordStore?.saveConfirmHitCount(current.wakeWordConfirmHits)
        wakeWordStore?.savePreset(current.wakeWordPreset)
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    phrase = phrase,
                    keywordScore = current.wakeWordKeywordScore,
                    keywordThreshold = current.wakeWordKeywordThreshold,
                    confirmHits = current.wakeWordConfirmHits,
                )
            }
        }
        syncService(forceRestart = state.read().wakeWordEnabled)
        appendLog(
            "唤醒配置已保存：$phrase，score=${current.wakeWordKeywordScore}，" +
                "threshold=${current.wakeWordKeywordThreshold}，confirm=${current.wakeWordConfirmHits}，" +
                "预设=${current.wakeWordPreset.label}",
        )
    }

    fun applyPreset(preset: WakeWordSensitivityPreset) {
        wakeWordStore?.applyPreset(preset)
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    preset = preset,
                    keywordScore = preset.keywordScore,
                    keywordThreshold = preset.keywordThreshold,
                    confirmHits = preset.confirmHits,
                )
            }
        }
        syncService(forceRestart = state.read().wakeWordEnabled)
        appendLog(
            "已切换唤醒预设「${preset.label}」：score=${preset.keywordScore}，" +
                "threshold=${preset.keywordThreshold}，二次确认=${preset.confirmHits}次",
        )
    }

    fun setSileroVadEnabled(enabled: Boolean) {
        wakeWordStore?.saveSileroVadEnabled(enabled)
        state.update { it.updateWakeWord { w -> w.copy(sileroVadEnabled = enabled) } }
        syncService(forceRestart = state.read().wakeWordEnabled)
        appendLog(if (enabled) "Silero VAD 已开启" else "Silero VAD 已关闭（回退 RMS）")
    }

    fun setSecondStageEnabled(enabled: Boolean) {
        wakeWordStore?.saveSecondStageEnabled(enabled)
        state.update { it.updateWakeWord { w -> w.copy(secondStageEnabled = enabled) } }
        syncService(forceRestart = state.read().wakeWordEnabled)
        appendLog(if (enabled) "二阶段唤醒已开启" else "二阶段唤醒已关闭")
    }

    fun testWakeWord(application: Application) {
        if (!hasRecordAudioPermission(application)) {
            requestRecordAudioForWakeWord(
                RuntimePermissionPrompt(
                    kind = RuntimePermissionKind.RecordAudio,
                    title = "需要麦克风权限",
                    message = "测试唤醒词需要麦克风权限。",
                ),
            )
            return
        }
        val phrase = state.read().wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        if (!state.read().wakeWordEnabled) {
            wakeWordStore?.saveEnabled(true)
            state.update { it.updateWakeWord { w -> w.copy(enabled = true, running = true) } }
        }
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    lastDetectedAtMs = null,
                    lastKeyword = null,
                    testHint = "请说唤醒词：$phrase",
                )
            }
        }
        syncService()
        appendLog("开始测试唤醒词：请说「$phrase」")
    }

    fun startCalibration(application: Application) {
        if (!hasRecordAudioPermission(application)) {
            appendLog("无法开始唤醒标定：请先授予麦克风权限")
            return
        }
        calibrationJob?.cancel()
        calibrationSession?.release()
        val phrase = state.read().wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
        val score = state.read().wakeWordKeywordScore
        val threshold = state.read().wakeWordKeywordThreshold
        pauseForMicSharing()
        calibrationSession = WakeWordCalibrationSession(application, phrase, score, threshold)
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    calibrationRunning = true,
                    calibrationStep = 0,
                    calibrationHint = "标定步骤 1/4：请清晰说出「$phrase」后点「录制样本」",
                )
            }
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

    fun recordCalibrationStep() {
        val session = calibrationSession ?: return
        if (!state.read().wakeWordCalibrationRunning) return
        val step = state.read().wakeWordCalibrationStep
        calibrationJob?.cancel()
        calibrationJob = mainScope.launch(Dispatchers.IO) {
            val phrase = state.read().wakeWordPhrase.trim().ifBlank { WakeWordConfigStore.DEFAULT_PHRASE }
            when {
                step < WakeWordCalibrationSession.POSITIVE_TARGET -> {
                    appendLog("正在录制唤醒样本 ${step + 1}/${WakeWordCalibrationSession.POSITIVE_TARGET}…")
                    val ok = session.recordPositiveSample()
                    if (!ok) {
                        appendLog("录制失败，请检查麦克风权限")
                        return@launch
                    }
                    val next = step + 1
                    state.update {
                        it.updateWakeWord { w ->
                            w.copy(
                                calibrationStep = next,
                                calibrationHint = if (next < WakeWordCalibrationSession.POSITIVE_TARGET) {
                                    "标定步骤 ${next + 1}/4：再说一次「$phrase」"
                                } else {
                                    "标定步骤 4/4：保持安静 5 秒，点「录制环境音」"
                                },
                            )
                        }
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
                    speakerProfileProvider()?.recordCalibrationPhrase(phrase)
                    state.update {
                        it.updateWakeWord { w ->
                            w.copy(
                                keywordThreshold = result.recommendedThreshold,
                                keywordScore = result.recommendedScore,
                                calibrated = true,
                                calibrationStep = WakeWordCalibrationSession.POSITIVE_TARGET + 1,
                                calibrationHint =
                                    "标定完成：threshold=${result.recommendedThreshold}，" +
                                        "正样本命中率=${"%.0f".format(result.positiveHitRate * 100)}%，" +
                                        "环境误触=${"%.0f".format(result.negativeHitRate * 100)}%",
                            )
                        }
                    }
                    appendLog(
                        "唤醒标定完成：threshold=${result.recommendedThreshold}，" +
                            "正样本 ${result.positiveHitRate}，环境误触 ${result.negativeHitRate}",
                    )
                    finishCalibration(resetOnly = false)
                    syncService(forceRestart = state.read().wakeWordEnabled)
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
            state.update {
                it.updateWakeWord { w ->
                    w.copy(
                        calibrationRunning = false,
                        calibrationStep = 0,
                        calibrationHint = null,
                    )
                }
            }
            ensureRunning()
        } else {
            state.update { it.updateWakeWord { w -> w.copy(calibrationRunning = false) } }
        }
    }

    fun runMigrationsIfNeeded() {
        migrateDefaultsIfNeeded()
        migrateAntiFalsePositiveIfNeeded()
        migrateQualityIfNeeded()
    }

    fun preloadModelsIfNeeded() {
        if (!BuildConfig.DEBUG) return
        val app = applicationProvider() ?: return
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

    fun onRecordAudioGranted(application: Application, granted: Boolean) {
        state.update { it.updatePermissions { p -> p.copy(recordAudioGranted = granted) } }
        if (granted) {
            appendLog("麦克风权限已授予")
            syncService(forceRestart = state.read().wakeWordEnabled)
        } else {
            appendLog("麦克风权限被拒绝，语音识别与本地唤醒不可用")
            WakeWordService.stop(application)
            state.update { it.updateWakeWord { w -> w.copy(running = false) } }
        }
    }

    fun seedStateFromStore(store: WakeWordConfigStore) {
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    enabled = store.isEnabled(),
                    phrase = store.getPhrase(),
                    running = store.isEnabled() && WakeWordService.isRunning,
                    keywordScore = store.getKeywordScore(),
                    keywordThreshold = store.getKeywordThreshold(),
                    confirmHits = store.getConfirmHitCount(),
                    preset = store.getPreset(),
                    calibrated = store.isCalibrated(),
                    sileroVadEnabled = store.isSileroVadEnabled(),
                    secondStageEnabled = store.isSecondStageEnabled(),
                )
            }
        }
    }

    private fun migrateDefaultsIfNeeded() {
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
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    keywordScore = store.getKeywordScore(),
                    keywordThreshold = store.getKeywordThreshold(),
                    confirmHits = store.getConfirmHitCount(),
                    preset = store.getPreset(),
                    sileroVadEnabled = store.isSileroVadEnabled(),
                    secondStageEnabled = store.isSecondStageEnabled(),
                )
            }
        }
        appendLog("已自动优化唤醒灵敏度配置（提升召回率）")
    }

    private fun migrateAntiFalsePositiveIfNeeded() {
        val store = wakeWordStore ?: return
        if (!store.needsAntiFalsePositiveMigration()) return
        val threshold = store.getKeywordThreshold()
        val confirmHits = store.getConfirmHitCount()
        val score = store.getKeywordScore()
        val tooSensitive = threshold <= 0.012f || confirmHits <= 1 || score >= 3.5f
        if (tooSensitive && store.getPreset() != WakeWordSensitivityPreset.SENSITIVE) {
            store.applyPreset(WakeWordSensitivityPreset.BALANCED)
            store.saveSecondStageEnabled(true)
            state.update {
                it.updateWakeWord { w ->
                    w.copy(
                        keywordScore = store.getKeywordScore(),
                        keywordThreshold = store.getKeywordThreshold(),
                        confirmHits = store.getConfirmHitCount(),
                        preset = store.getPreset(),
                        secondStageEnabled = store.isSecondStageEnabled(),
                    )
                }
            }
            appendLog("已收紧唤醒灵敏度，降低误触（二次确认 + 二阶段复检）")
        }
        store.markAntiFalsePositiveMigrationDone()
    }

    private fun migrateQualityIfNeeded() {
        val store = wakeWordStore ?: return
        if (!store.needsWakeQualityMigration()) return
        val preset = store.getPreset()
        if (preset != WakeWordSensitivityPreset.SENSITIVE || store.getConfirmHitCount() <= 1) {
            store.applyPreset(WakeWordSensitivityPreset.BALANCED)
        }
        store.saveVadGateEnabled(true)
        store.saveSecondStageEnabled(true)
        store.saveSileroVadEnabled(true)
        state.update {
            it.updateWakeWord { w ->
                w.copy(
                    keywordScore = store.getKeywordScore(),
                    keywordThreshold = store.getKeywordThreshold(),
                    confirmHits = store.getConfirmHitCount(),
                    preset = store.getPreset(),
                    sileroVadEnabled = store.isSileroVadEnabled(),
                    secondStageEnabled = store.isSecondStageEnabled(),
                )
            }
        }
        store.markWakeQualityMigrationDone()
        appendLog("已优化唤醒：启用语音活动门控 + 二次确认，降低环境噪音误触")
    }
}
