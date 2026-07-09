package com.tetraploid.joyforold.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentUiState
import kotlinx.coroutines.flow.StateFlow

class DemoViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<AgentUiState> = AgentRuntime.state

    init {
        AgentRuntime.initIfNeeded(application)
    }

    fun refreshAccessibilityState() = AgentRuntime.refreshAccessibilityState()

    fun updateApiKey(value: String) = AgentRuntime.updateApiKey(value)

    fun saveApiKey() = AgentRuntime.saveApiKey(getApplication())

    fun updateCommand(value: String) = AgentRuntime.updateCommand(value)

    fun runAgent() = AgentRuntime.runAgent(getApplication())

    fun previewPageTree() = AgentRuntime.previewPageTree()

    fun startVoiceInput() = AgentRuntime.startVoiceInput()

    fun stopVoiceInput() = AgentRuntime.stopVoiceInput()

    fun onVoicePermissionDenied() = AgentRuntime.appendLog("缺少录音权限，无法开始语音输入")

    fun stopVoiceInputAndRunAgent() = AgentRuntime.stopVoiceInputAndRunAgent(getApplication())

    fun startVoiceReplyToConfirm() = AgentRuntime.startVoiceReplyToConfirm(getApplication())

    fun clearPendingConfirmUI() = AgentRuntime.clearPendingConfirmUI()

    fun cancelAgent() = AgentRuntime.cancelAgent()

    fun pauseAgent() = AgentRuntime.pauseAgent()

    fun resumeAgent() = AgentRuntime.resumeAgent()
}
