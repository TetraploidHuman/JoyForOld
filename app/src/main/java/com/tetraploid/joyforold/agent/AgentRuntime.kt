package com.tetraploid.joyforold.agent

import android.app.Application
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.speech.DoubaoAsrClient
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
)

object AgentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val orchestrator = AgentOrchestrator()
    private var apiKeyStore: ApiKeyStore? = null
    private var memoryStore: AgentMemoryStore? = null
    private var asrClient: DoubaoAsrClient? = null
    private var voiceConfirmReplyMode = false
    private var voiceReplyApplication: Application? = null
    private var agentJob: Job? = null
    private var runContext: AgentRunContext? = null

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun initIfNeeded(application: Application) {
        if (apiKeyStore == null) {
            apiKeyStore = ApiKeyStore(application)
            memoryStore = AgentMemoryStore(application).also { orchestrator.bindMemoryStore(it) }
            refreshMemories()
            _state.update {
                it.copy(
                    apiKey = apiKeyStore!!.getApiKey(),
                    modelName = apiKeyStore!!.getModel(),
                )
            }
        }
    }

    fun refreshAccessibilityState() {
        _state.update { it.copy(accessibilityEnabled = JoyAccessibilityService.instance != null) }
    }

    private fun refreshMemories() {
        val summaries = memoryStore?.loadRecentMemories()?.map { it.summary }.orEmpty()
        _state.update { it.copy(recentMemories = summaries) }
    }

    fun updateApiKey(value: String) {
        _state.update { it.copy(apiKey = value) }
    }

    fun saveApiKey(application: Application) {
        initIfNeeded(application)
        apiKeyStore?.saveApiKey(_state.value.apiKey)
        appendLog("API Key 已保存")
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
            appendLog("语音识别未配置：请在 local.properties 设置 volc.asr.api_key")
            return
        }
        voiceConfirmReplyMode = confirmReplyMode
        voiceReplyApplication = application
        _state.update { it.copy(isListening = true, speechText = "") }
        appendLog(if (confirmReplyMode) "请用语音回答..." else "开始语音识别...")
        client.start(
            onPartialText = { text ->
                scope.launch {
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
                    voiceConfirmReplyMode = false
                    voiceReplyApplication = null
                    _state.update { it.copy(isListening = false) }
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
                startVoiceReplyToConfirm(app)
            }
            return
        }

        if (app == null) return

        if (forceRun || isConfirmReply) {
            appendLog("继续执行：$merged")
            runAgent(app)
        }
    }

    fun appendLog(message: String) {
        _state.update { it.copy(logs = (it.logs + message).takeLast(100)) }
    }

    fun runAgent(application: Application) {
        initIfNeeded(application)
        val current = _state.value
        if (current.isRunning) return

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
            FloatingOverlayService.collapsePanel()
            val startedAt = System.currentTimeMillis()
            appendLog("开始执行：${current.command}")

            try {
                val result = orchestrator.run(
                    userCommand = current.command,
                    apiKey = current.apiKey.ifBlank { apiKeyStore?.getApiKey().orEmpty() },
                    runContext = context,
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
                if (result.waitingForUserConfirm) {
                    FloatingOverlayService.expandPanel()
                    startVoiceReplyToConfirm(application)
                }
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(isRunning = false, isPaused = false, statusMessage = "已停止")
                }
            } finally {
                agentJob = null
                runContext = null
            }
        }
    }

    fun clearPendingConfirmUI() {
        voiceConfirmReplyMode = false
        voiceReplyApplication = null
        orchestrator.clearPendingUserReply()
        if (_state.value.isListening) stopVoiceInput()
        _state.update { it.copy(waitingForUserConfirm = false, confirmPrompt = null) }
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
        if (asrClient != null) return asrClient
        val hasNewApiKey = BuildConfig.VOLC_ASR_API_KEY.isNotBlank()
        val hasLegacyAuth = BuildConfig.VOLC_ASR_APP_ID.isNotBlank() &&
            BuildConfig.VOLC_ASR_ACCESS_TOKEN.isNotBlank()
        if (!hasNewApiKey && !hasLegacyAuth) return null
        asrClient = DoubaoAsrClient(
            apiKey = BuildConfig.VOLC_ASR_API_KEY,
            appId = BuildConfig.VOLC_ASR_APP_ID,
            accessToken = BuildConfig.VOLC_ASR_ACCESS_TOKEN,
            resourceId = BuildConfig.VOLC_ASR_RESOURCE_ID,
        )
        return asrClient
    }
}
