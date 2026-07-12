package com.tetraploid.joyforold.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import kotlinx.coroutines.flow.StateFlow

class DemoViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<AgentUiState> = AgentRuntime.state

    init {
        AgentRuntime.initIfNeeded(application)
    }

    fun refreshAccessibilityState() = AgentRuntime.refreshAccessibilityState()

    fun onRecordAudioPermissionResult(granted: Boolean) =
        AgentRuntime.onRecordAudioPermissionResult(getApplication(), granted)

    fun updateApiKey(value: String) = AgentRuntime.updateApiKey(value)

    fun saveApiKey() = AgentRuntime.saveApiKey(getApplication())

    fun updateAsrApiKey(value: String) = AgentRuntime.updateAsrApiKey(value)

    fun updateAsrAppId(value: String) = AgentRuntime.updateAsrAppId(value)

    fun updateAsrAccessToken(value: String) = AgentRuntime.updateAsrAccessToken(value)

    fun updateAsrResourceId(value: String) = AgentRuntime.updateAsrResourceId(value)

    fun saveAsrConfig() = AgentRuntime.saveAsrConfig(getApplication())

    fun updateWakeWordPhrase(value: String) = AgentRuntime.updateWakeWordPhrase(value)

    fun updateWakeWordKeywordScore(value: String) = AgentRuntime.updateWakeWordKeywordScore(value)

    fun updateWakeWordKeywordThreshold(value: String) = AgentRuntime.updateWakeWordKeywordThreshold(value)

    fun updateDaughterPhone(value: String) = AgentRuntime.updateDaughterPhone(value)

    fun updateSonPhone(value: String) = AgentRuntime.updateSonPhone(value)

    fun updateEmergencyPhone(value: String) = AgentRuntime.updateEmergencyPhone(value)

    fun updateEmergencyMessage(value: String) = AgentRuntime.updateEmergencyMessage(value)

    fun updateHomeAddress(value: String) = AgentRuntime.updateHomeAddress(value)

    fun updatePresetPhraseGoHome(value: String) = AgentRuntime.updatePresetPhraseGoHome(value)

    fun saveCaregiverSettings() = AgentRuntime.saveCaregiverSettings(getApplication())

    fun applyWakeWordPreset(preset: WakeWordSensitivityPreset) =
        AgentRuntime.applyWakeWordPreset(getApplication(), preset)

    fun saveWakeWordConfig() = AgentRuntime.saveWakeWordConfig(getApplication())

    fun setWakeWordEnabled(enabled: Boolean) = AgentRuntime.setWakeWordEnabled(getApplication(), enabled)

    fun setWakeWordSileroVadEnabled(enabled: Boolean) =
        AgentRuntime.setWakeWordSileroVadEnabled(getApplication(), enabled)

    fun setWakeWordSecondStageEnabled(enabled: Boolean) =
        AgentRuntime.setWakeWordSecondStageEnabled(getApplication(), enabled)

    fun testWakeWord() = AgentRuntime.testWakeWord(getApplication())

    fun startWakeWordCalibration() = AgentRuntime.startWakeWordCalibration(getApplication())

    fun recordCalibrationStep() = AgentRuntime.recordCalibrationStep(getApplication())

    fun updateCommand(value: String) = AgentRuntime.updateCommand(value)

    fun submitCommand(command: String) {
        AgentRuntime.updateCommand(command)
        AgentRuntime.runAgent(getApplication())
    }

    fun runAgent() = AgentRuntime.runAgent(getApplication())

    fun previewPageTree() = AgentRuntime.previewPageTree()

    fun startVoiceInput() = AgentRuntime.startVoiceInput()

    fun resumeWakeWordVoiceSession() = AgentRuntime.resumeWakeWordVoiceSession()

    fun clearPermissionPrompt() = AgentRuntime.clearPermissionPrompt()

    fun stopVoiceInput() = AgentRuntime.stopVoiceInput()

    fun onReadContactsPermissionResult(granted: Boolean) =
        AgentRuntime.onReadContactsPermissionResult(getApplication(), granted)

    fun onVoicePermissionDenied() = AgentRuntime.appendLog("缺少录音权限，无法开始语音输入")

    fun stopVoiceInputAndRunAgent() = AgentRuntime.stopVoiceInputAndRunAgent(getApplication())

    fun startVoiceReplyToConfirm() = AgentRuntime.startVoiceReplyToConfirm(getApplication())

    fun clearPendingConfirmUI() = AgentRuntime.clearPendingConfirmUI()

    fun submitBinaryConfirm(approved: Boolean) = AgentRuntime.submitBinaryConfirm(approved)

    fun setCloudContextConsent(granted: Boolean) =
        AgentRuntime.setCloudContextConsent(getApplication(), granted)

    fun setVoiceBargeInEnabled(enabled: Boolean) =
        AgentRuntime.setVoiceBargeInEnabled(getApplication(), enabled)

    fun selectDisambiguationOption(intentId: String) = AgentRuntime.selectDisambiguationOption(intentId)

    fun undoLastLocalAction() = AgentRuntime.undoLastLocalAction()

    fun dismissUndoOffer() = AgentRuntime.dismissUndoOffer()

    fun clearInteraction() = AgentRuntime.clearInteraction()

    fun cancelAgent() = AgentRuntime.cancelAgent()

    fun pauseAgent() = AgentRuntime.pauseAgent()

    fun resumeAgent() = AgentRuntime.resumeAgent()
}
