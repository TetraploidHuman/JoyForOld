package com.tetraploid.joyforold.agent.runtime

import android.app.Application
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.collaboration.AssistPairingStore
import com.tetraploid.joyforold.collaboration.AssistSessionCallbacks
import com.tetraploid.joyforold.collaboration.AssistSessionManager
import com.tetraploid.joyforold.collaboration.AssistSessionPhase
import com.tetraploid.joyforold.collaboration.AssistSessionSnapshot
import kotlinx.coroutines.CoroutineScope

/**
 * 远程协助会话在 AgentRuntime 中的桥接层。
 */
internal class AssistRuntimeBridge(
    private val state: AgentStateAccessor,
    private val agentScope: CoroutineScope,
    private val onAssistModeChanged: (Boolean) -> Unit,
    private val onRemoteCommand: (Application, String) -> Unit,
) {
    private var sessionManager: AssistSessionManager? = null

    @Volatile
    private var assistModeActive = false

    var remoteCommandRun: Boolean = false

    fun attachSessionManager(
        application: Application,
        store: AssistPairingStore,
    ) {
        if (sessionManager != null) return
        sessionManager = AssistSessionManager(
            application = application,
            scope = agentScope,
            store = store,
            callbacks = AssistSessionCallbacks(
                onAssistModeChanged = { active ->
                    assistModeActive = active
                    onAssistModeChanged(active)
                },
                onRemoteCommand = onRemoteCommand,
            ),
            onSnapshot = ::applySnapshot,
        ).also {
            it.refreshConfig(
                defaultHttp = BuildConfig.ASSIST_SERVER_URL,
                defaultWs = BuildConfig.ASSIST_SERVER_WS,
            )
        }
    }

    fun manager(): AssistSessionManager? = sessionManager

    fun refreshConfig() {
        sessionManager?.refreshConfig(
            defaultHttp = BuildConfig.ASSIST_SERVER_URL,
            defaultWs = BuildConfig.ASSIST_SERVER_WS,
        )
    }

    fun isAssistModeActive(): Boolean = assistModeActive

    fun blocksLocalAgent(): Boolean {
        val snapshot = state.read()
        return snapshot.assistRole == AssistRole.CAREGIVER &&
            snapshot.assistPhase == AssistSessionPhase.ACTIVE
    }

    fun relayRunningStatus(message: String) {
        if (!remoteCommandRun) return
        sessionManager?.relayAgentStatus("running", message)
    }

    fun relayAgentStatus(success: Boolean, summary: String) {
        if (!remoteCommandRun) return
        remoteCommandRun = false
        sessionManager?.relayAgentStatus(
            status = if (success) "done" else "failed",
            message = summary.ifBlank { if (success) "执行完成" else "执行失败" },
        )
    }

    fun requestNavigation() {
        state.update { it.copy(assistNavigateTick = it.assistNavigateTick + 1) }
    }

    private fun applySnapshot(snapshot: AssistSessionSnapshot) {
        state.update {
            it.copy(
                assistRole = snapshot.role,
                assistPhase = snapshot.phase,
                assistPairCode = snapshot.pairCode,
                assistSessionId = snapshot.sessionId,
                assistStatusMessage = snapshot.statusMessage,
                assistPeerDisplayName = snapshot.peerDisplayName,
                assistLatestFrameBytes = snapshot.latestFrameBytes,
                assistLatestFrameWidth = snapshot.latestFrameWidth,
                assistLatestFrameHeight = snapshot.latestFrameHeight,
                assistLatestFrameFormat = snapshot.latestFrameFormat,
                assistBindings = snapshot.bindings,
                assistServerHttpUrl = snapshot.serverHttpUrl,
                assistServerWsUrl = snapshot.serverWsUrl,
                assistDisplayName = snapshot.displayName,
                assistStreamFps = snapshot.streamFps,
                assistStreamLatencyMs = snapshot.streamLatencyMs,
            )
        }
    }

    // --- 对外 API（供 AgentRuntime 委托）---

    fun setRole(role: AssistRole) = sessionManager?.setRole(role)

    fun setDisplayName(name: String) = sessionManager?.setDisplayName(name)

    fun setServerHttpUrl(url: String) = sessionManager?.setServerHttpUrl(url)

    fun setServerWsUrl(url: String) = sessionManager?.setServerWsUrl(url)

    fun startElderSession() = sessionManager?.startElderSession()

    fun joinWithPairCode(pairCode: String) = sessionManager?.joinWithPairCode(pairCode)

    fun connectBinding(binding: BindingDto) = sessionManager?.connectBinding(binding)

    fun deleteBinding(bindingId: String) = sessionManager?.deleteBinding(bindingId)

    fun sendTap(x: Int, y: Int) = sessionManager?.sendTap(x, y)

    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int) =
        sessionManager?.sendSwipe(x1, y1, x2, y2)

    fun sendAction(name: String) = sessionManager?.sendAction(name)

    fun sendTypeText(text: String) = sessionManager?.sendTypeText(text)

    fun sendCommand(text: String) = sessionManager?.sendCommand(text)

    fun endSession() = sessionManager?.endSession()
}
