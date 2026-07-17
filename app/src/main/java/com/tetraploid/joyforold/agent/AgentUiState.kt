package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.collaboration.AssistSessionPhase
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import com.tetraploid.joyforold.wakeword.SherpaOnnxModelManager
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset

data class AgentPermissionUiState(
    val accessibilityEnabled: Boolean = false,
    val accessibilityServiceConnected: Boolean = false,
    val accessibilityWhitelistReaderEnabled: Boolean = false,
    val accessibilityWhitelistReaderConnected: Boolean = false,
    val joyImeEnabled: Boolean = false,
    val joyImeSelectedAsDefault: Boolean = false,
    val recordAudioGranted: Boolean = false,
    val readContactsGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val permissionPrompt: RuntimePermissionPrompt? = null,
)

data class AgentWakeWordUiState(
    val enabled: Boolean = false,
    val phrase: String = "",
    val running: Boolean = false,
    val lastDetectedAtMs: Long? = null,
    val lastKeyword: String? = null,
    val testHint: String? = null,
    val keywordScore: Float = WakeWordConfigStore.DEFAULT_KEYWORD_SCORE,
    val keywordThreshold: Float = WakeWordConfigStore.DEFAULT_KEYWORD_THRESHOLD,
    val confirmHits: Int = WakeWordConfigStore.DEFAULT_CONFIRM_HITS,
    val preset: WakeWordSensitivityPreset = WakeWordSensitivityPreset.BALANCED,
    val modelVersion: String = SherpaOnnxModelManager.MODEL_VERSION,
    val calibrationRunning: Boolean = false,
    val calibrationStep: Int = 0,
    val calibrationHint: String? = null,
    val calibrated: Boolean = false,
    val sileroVadEnabled: Boolean = WakeWordConfigStore.DEFAULT_SILERO_VAD,
    val secondStageEnabled: Boolean = WakeWordConfigStore.DEFAULT_SECOND_STAGE,
)

data class AgentAssistUiState(
    val role: AssistRole = AssistRole.ELDER,
    val phase: AssistSessionPhase = AssistSessionPhase.IDLE,
    val pairCode: String = "",
    val sessionId: String = "",
    val statusMessage: String = "",
    val peerDisplayName: String = "",
    val latestFrameBytes: ByteArray? = null,
    val latestFrameWidth: Int = 0,
    val latestFrameHeight: Int = 0,
    val latestFrameFormat: String = "",
    val bindings: List<BindingDto> = emptyList(),
    val serverHttpUrl: String = "",
    val serverWsUrl: String = "",
    val displayName: String = "",
    val streamFps: Float = 0f,
    val streamLatencyMs: Long = -1L,
    val mode: Boolean = false,
    val navigateTick: Long = 0L,
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
    val permissions: AgentPermissionUiState = AgentPermissionUiState(),
    val waitingForUserConfirm: Boolean = false,
    val confirmPrompt: String? = null,
    val needsBinaryConfirm: Boolean = false,
    val currentStep: Int = 0,
    val statusMessage: String = "",
    val taskSteps: List<TaskStepItem> = emptyList(),
    val taskPhases: List<TaskPhaseItem> = emptyList(),
    val conversationCards: List<ConversationCard> = emptyList(),
    val overlayInteractionCard: ConversationCard? = null,
    /** 悬浮层会话卡片（计划/进度/确认）；视觉模式下为空 */
    val overlaySessionCards: List<ConversationCard> = emptyList(),
    val sessionId: String? = null,
    val recentMemories: List<String> = emptyList(),
    val wakeWord: AgentWakeWordUiState = AgentWakeWordUiState(),
    val daughterPhone: String = "",
    val sonPhone: String = "",
    val emergencyPhone: String = "",
    val emergencyMessage: String = "",
    val homeAddress: String = "",
    val presetPhraseGoHome: String = "我要回家, 导航回家, 送我回家",
    val suggestionChips: List<String> = emptyList(),
    val cloudContextConsentGranted: Boolean = false,
    val voiceBargeInEnabled: Boolean = true,
    val visionDebugEnabled: Boolean = false,
    val visionDebugFrames: List<VisionDebugFrame> = emptyList(),
    /** 持续把主应用 UI 树（snapshotForAgent）输出到 Logcat */
    val uiTreeLogcatEnabled: Boolean = false,
    /** 视觉兜底模式：隐藏悬浮交互卡片，截图/tap 期间仍由 overlay suppression 临时隐藏整层 */
    val visionAgentActive: Boolean = false,
    val assist: AgentAssistUiState = AgentAssistUiState(),
) {
    val accessibilityEnabled get() = permissions.accessibilityEnabled
    val accessibilityServiceConnected get() = permissions.accessibilityServiceConnected
    val accessibilityWhitelistReaderEnabled get() = permissions.accessibilityWhitelistReaderEnabled
    val accessibilityWhitelistReaderConnected get() = permissions.accessibilityWhitelistReaderConnected
    val joyImeEnabled get() = permissions.joyImeEnabled
    val joyImeSelectedAsDefault get() = permissions.joyImeSelectedAsDefault
    val recordAudioGranted get() = permissions.recordAudioGranted
    val readContactsGranted get() = permissions.readContactsGranted
    val notificationAccessGranted get() = permissions.notificationAccessGranted
    val permissionPrompt get() = permissions.permissionPrompt

    val wakeWordEnabled get() = wakeWord.enabled
    val wakeWordPhrase get() = wakeWord.phrase
    val wakeWordRunning get() = wakeWord.running
    val lastWakeWordAtMs get() = wakeWord.lastDetectedAtMs
    val lastWakeWordKeyword get() = wakeWord.lastKeyword
    val wakeWordTestHint get() = wakeWord.testHint
    val wakeWordKeywordScore get() = wakeWord.keywordScore
    val wakeWordKeywordThreshold get() = wakeWord.keywordThreshold
    val wakeWordConfirmHits get() = wakeWord.confirmHits
    val wakeWordPreset get() = wakeWord.preset
    val wakeWordModelVersion get() = wakeWord.modelVersion
    val wakeWordCalibrationRunning get() = wakeWord.calibrationRunning
    val wakeWordCalibrationStep get() = wakeWord.calibrationStep
    val wakeWordCalibrationHint get() = wakeWord.calibrationHint
    val wakeWordCalibrated get() = wakeWord.calibrated
    val wakeWordSileroVadEnabled get() = wakeWord.sileroVadEnabled
    val wakeWordSecondStageEnabled get() = wakeWord.secondStageEnabled

    val assistRole get() = assist.role
    val assistPhase get() = assist.phase
    val assistPairCode get() = assist.pairCode
    val assistSessionId get() = assist.sessionId
    val assistStatusMessage get() = assist.statusMessage
    val assistPeerDisplayName get() = assist.peerDisplayName
    val assistLatestFrameBytes get() = assist.latestFrameBytes
    val assistLatestFrameWidth get() = assist.latestFrameWidth
    val assistLatestFrameHeight get() = assist.latestFrameHeight
    val assistLatestFrameFormat get() = assist.latestFrameFormat
    val assistBindings get() = assist.bindings
    val assistServerHttpUrl get() = assist.serverHttpUrl
    val assistServerWsUrl get() = assist.serverWsUrl
    val assistDisplayName get() = assist.displayName
    val assistStreamFps get() = assist.streamFps
    val assistStreamLatencyMs get() = assist.streamLatencyMs
    val assistMode get() = assist.mode
    val assistNavigateTick get() = assist.navigateTick
}

fun AgentUiState.updateWakeWord(transform: (AgentWakeWordUiState) -> AgentWakeWordUiState): AgentUiState =
    copy(wakeWord = transform(wakeWord))

fun AgentUiState.updatePermissions(transform: (AgentPermissionUiState) -> AgentPermissionUiState): AgentUiState =
    copy(permissions = transform(permissions))

fun AgentUiState.updateAssist(transform: (AgentAssistUiState) -> AgentAssistUiState): AgentUiState =
    copy(assist = transform(assist))
