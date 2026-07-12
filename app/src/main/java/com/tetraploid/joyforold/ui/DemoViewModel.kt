package com.tetraploid.joyforold.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
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

    fun setVisionDebugEnabled(enabled: Boolean) =
        AgentRuntime.setVisionDebugEnabled(getApplication(), enabled)

    fun refreshVisionDebugFrames() = AgentRuntime.refreshVisionDebugFrames()

    fun clearVisionDebugFrames() = AgentRuntime.clearVisionDebugFrames()

    fun setAssistRole(role: AssistRole) = AgentRuntime.setAssistRole(role)

    fun setAssistDisplayName(name: String) = AgentRuntime.setAssistDisplayName(name)

    fun setAssistServerHttpUrl(url: String) = AgentRuntime.setAssistServerHttpUrl(url)

    fun setAssistServerWsUrl(url: String) = AgentRuntime.setAssistServerWsUrl(url)

    fun startElderAssistSession() = AgentRuntime.startElderAssistSession()

    fun joinAssistSession(pairCode: String) = AgentRuntime.joinAssistSession(pairCode)

    fun connectAssistBinding(binding: BindingDto) = AgentRuntime.connectAssistBinding(binding)

    fun deleteAssistBinding(bindingId: String) = AgentRuntime.deleteAssistBinding(bindingId)

    fun sendAssistTap(x: Int, y: Int) = AgentRuntime.sendAssistTap(x, y)

    fun sendAssistSwipe(x1: Int, y1: Int, x2: Int, y2: Int) =
        AgentRuntime.sendAssistSwipe(x1, y1, x2, y2)

    fun sendAssistAction(name: String) = AgentRuntime.sendAssistAction(name)

    fun sendAssistTypeText(text: String) = AgentRuntime.sendAssistTypeText(text)

    fun sendAssistCommand(text: String) = AgentRuntime.sendAssistCommand(text)

    fun endAssistSession() = AgentRuntime.endAssistSession()

    fun refreshAssistConfig() = AgentRuntime.refreshAssistConfig()

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
