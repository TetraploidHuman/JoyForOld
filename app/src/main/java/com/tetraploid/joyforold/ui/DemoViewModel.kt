package com.tetraploid.joyforold.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.wakeword.WakeWordSensitivityPreset
import kotlinx.coroutines.flow.StateFlow

class DemoViewModel(
    application: Application,
    private val runtime: AgentRuntime,
) : AndroidViewModel(application) {
    val uiState: StateFlow<AgentUiState> = runtime.state

    fun refreshAccessibilityState() = runtime.refreshAccessibilityState()

    fun onRecordAudioPermissionResult(granted: Boolean) =
        runtime.onRecordAudioPermissionResult(getApplication(), granted)

    fun updateApiKey(value: String) = runtime.updateApiKey(value)

    fun saveApiKey() = runtime.saveApiKey(getApplication())

    fun updateAsrApiKey(value: String) = runtime.updateAsrApiKey(value)

    fun updateAsrAppId(value: String) = runtime.updateAsrAppId(value)

    fun updateAsrAccessToken(value: String) = runtime.updateAsrAccessToken(value)

    fun updateAsrResourceId(value: String) = runtime.updateAsrResourceId(value)

    fun saveAsrConfig() = runtime.saveAsrConfig(getApplication())

    fun updateWakeWordPhrase(value: String) = runtime.updateWakeWordPhrase(value)

    fun updateWakeWordKeywordScore(value: String) = runtime.updateWakeWordKeywordScore(value)

    fun updateWakeWordKeywordThreshold(value: String) = runtime.updateWakeWordKeywordThreshold(value)

    fun updateDaughterPhone(value: String) = runtime.updateDaughterPhone(value)

    fun updateSonPhone(value: String) = runtime.updateSonPhone(value)

    fun updateEmergencyPhone(value: String) = runtime.updateEmergencyPhone(value)

    fun updateEmergencyMessage(value: String) = runtime.updateEmergencyMessage(value)

    fun updateHomeAddress(value: String) = runtime.updateHomeAddress(value)

    fun updatePresetPhraseGoHome(value: String) = runtime.updatePresetPhraseGoHome(value)

    fun saveCaregiverSettings() = runtime.saveCaregiverSettings(getApplication())

    fun applyWakeWordPreset(preset: WakeWordSensitivityPreset) =
        runtime.applyWakeWordPreset(getApplication(), preset)

    fun saveWakeWordConfig() = runtime.saveWakeWordConfig(getApplication())

    fun setWakeWordEnabled(enabled: Boolean) = runtime.setWakeWordEnabled(getApplication(), enabled)

    fun setWakeWordSileroVadEnabled(enabled: Boolean) =
        runtime.setWakeWordSileroVadEnabled(getApplication(), enabled)

    fun setWakeWordSecondStageEnabled(enabled: Boolean) =
        runtime.setWakeWordSecondStageEnabled(getApplication(), enabled)

    fun testWakeWord() = runtime.testWakeWord(getApplication())

    fun startWakeWordCalibration() = runtime.startWakeWordCalibration(getApplication())

    fun recordCalibrationStep() = runtime.recordCalibrationStep(getApplication())

    fun updateCommand(value: String) = runtime.updateCommand(value)

    fun submitCommand(command: String) {
        runtime.updateCommand(command)
        runtime.runAgent(getApplication())
    }

    fun runAgent() = runtime.runAgent(getApplication())

    fun previewPageTree() = runtime.previewPageTree()

    fun setVisionDebugEnabled(enabled: Boolean) =
        runtime.setVisionDebugEnabled(getApplication(), enabled)

    fun setUiTreeLogcatEnabled(enabled: Boolean) =
        runtime.setUiTreeLogcatEnabled(getApplication(), enabled)

    fun refreshVisionDebugFrames() = runtime.refreshVisionDebugFrames()

    fun clearVisionDebugFrames() = runtime.clearVisionDebugFrames()

    fun setAssistRole(role: AssistRole) = runtime.setAssistRole(role)

    fun setAssistDisplayName(name: String) = runtime.setAssistDisplayName(name)

    fun setAssistServerHttpUrl(url: String) = runtime.setAssistServerHttpUrl(url)

    fun setAssistServerWsUrl(url: String) = runtime.setAssistServerWsUrl(url)

    fun startElderAssistSession() = runtime.startElderAssistSession()

    fun joinAssistSession(pairCode: String) = runtime.joinAssistSession(pairCode)

    fun connectAssistBinding(binding: BindingDto) = runtime.connectAssistBinding(binding)

    fun deleteAssistBinding(bindingId: String) = runtime.deleteAssistBinding(bindingId)

    fun sendAssistTap(x: Int, y: Int) = runtime.sendAssistTap(x, y)

    fun sendAssistSwipe(x1: Int, y1: Int, x2: Int, y2: Int) =
        runtime.sendAssistSwipe(x1, y1, x2, y2)

    fun sendAssistAction(name: String) = runtime.sendAssistAction(name)

    fun sendAssistTypeText(text: String) = runtime.sendAssistTypeText(text)

    fun sendAssistCommand(text: String) = runtime.sendAssistCommand(text)

    fun endAssistSession() = runtime.endAssistSession()

    fun refreshAssistConfig() = runtime.refreshAssistConfig()

    fun startVoiceInput() = runtime.startVoiceInput()

    fun resumeWakeWordVoiceSession() = runtime.resumeWakeWordVoiceSession()

    fun clearPermissionPrompt() = runtime.clearPermissionPrompt()

    fun stopVoiceInput() = runtime.stopVoiceInput()

    fun onReadContactsPermissionResult(granted: Boolean) =
        runtime.onReadContactsPermissionResult(getApplication(), granted)

    fun onVoicePermissionDenied() = runtime.appendLog("缺少录音权限，无法开始语音输入")

    fun stopVoiceInputAndRunAgent() = runtime.stopVoiceInputAndRunAgent(getApplication())

    fun startVoiceReplyToConfirm() = runtime.startVoiceReplyToConfirm(getApplication())

    fun clearPendingConfirmUI() = runtime.clearPendingConfirmUI()

    fun submitBinaryConfirm(approved: Boolean) = runtime.submitBinaryConfirm(approved)

    fun setCloudContextConsent(granted: Boolean) =
        runtime.setCloudContextConsent(getApplication(), granted)

    fun setVoiceBargeInEnabled(enabled: Boolean) =
        runtime.setVoiceBargeInEnabled(getApplication(), enabled)

    fun selectDisambiguationOption(intentId: String) = runtime.selectDisambiguationOption(intentId)

    fun undoLastLocalAction() = runtime.undoLastLocalAction()

    fun dismissUndoOffer() = runtime.dismissUndoOffer()

    fun clearInteraction() = runtime.clearInteraction()

    fun cancelAgent() = runtime.cancelAgent()

    fun pauseAgent() = runtime.pauseAgent()

    fun resumeAgent() = runtime.resumeAgent()
}
