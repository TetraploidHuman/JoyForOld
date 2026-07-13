package com.tetraploid.joyforold.collaboration

import android.app.Application
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.assist.protocol.ConnectBindingRequest
import com.tetraploid.joyforold.assist.protocol.CreatePairRequest
import com.tetraploid.joyforold.assist.protocol.DeleteBindingRequest
import com.tetraploid.joyforold.assist.protocol.ElderSyncRequest
import com.tetraploid.joyforold.assist.protocol.EndPairRequest
import com.tetraploid.joyforold.assist.protocol.JoinPairRequest
import com.tetraploid.joyforold.assist.protocol.ListBindingsRequest
import com.tetraploid.joyforold.overlay.VisionOverlayGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AssistSessionManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: AssistPairingStore,
    private val callbacks: AssistSessionCallbacks = AssistSessionCallbacks(),
    private val apiClient: AssistApiClient = AssistApiClient(),
    private val onSnapshot: (AssistSessionSnapshot) -> Unit,
) : AssistRelayClient.Listener {
    private val commandExecutor = AssistCommandExecutor(
        scope = scope,
        onRemoteCommand = callbacks.onRemoteCommand,
    )
    private var relayClient = AssistRelayClient(this)
    private var snapshot = AssistSessionSnapshot()
    private var activeToken: String = ""
    private var frameLoopJob: Job? = null
    private var elderPollJob: Job? = null
    private var pendingFrameMeta: AssistControlMessage? = null
    private val remoteCommandMutex = Mutex()
    private val streamStats = AssistStreamStats()

    companion object {
        private val SCROLL_ACTIONS = setOf("scroll_up", "scroll_down", "swipe_up", "swipe_down")
    }

    fun currentSnapshot(): AssistSessionSnapshot = snapshot

    fun refreshConfig(defaultHttp: String, defaultWs: String) {
        val http = AssistEndpointUrls.normalizeHttpBase(
            raw = store.loadServerHttpUrl(),
            default = defaultHttp,
        )
        val ws = AssistEndpointUrls.normalizeWsBase(
            raw = store.loadServerWsUrl(),
            httpBase = http,
            default = defaultWs,
        )
        store.saveServerHttpUrl(http)
        store.saveServerWsUrl(ws)
        val role = store.loadRole()
        val displayName = store.loadDisplayName()
        updateSnapshot {
            it.copy(
                role = role,
                serverHttpUrl = http,
                serverWsUrl = ws,
                displayName = displayName,
            )
        }
        if (http.isNotBlank()) {
            scope.launch {
                val reachable = apiClient.pingHealth(http).getOrDefault(false)
                if (!reachable && snapshot.statusMessage.isBlank()) {
                    updateSnapshot {
                        it.copy(statusMessage = "协助服务器不可达：$http（请确认 PC 服务已启动且在同一 WiFi）")
                    }
                }
            }
        }
        refreshBindings()
        syncElderPoll()
    }

    private fun syncElderPoll() {
        if (snapshot.role == AssistRole.ELDER &&
            snapshot.phase != AssistSessionPhase.ACTIVE &&
            snapshot.serverHttpUrl.isNotBlank()
        ) {
            startElderPoll()
        } else {
            elderPollJob?.cancel()
            elderPollJob = null
        }
    }

    private fun startElderPoll() {
        if (elderPollJob?.isActive == true) return
        elderPollJob = scope.launch {
            while (isActive &&
                snapshot.role == AssistRole.ELDER &&
                snapshot.phase != AssistSessionPhase.ACTIVE
            ) {
                pollElderSession()
                delay(2_000)
            }
        }
    }

    private suspend fun pollElderSession() {
        val http = snapshot.serverHttpUrl
        if (http.isBlank()) return
        val response = apiClient.elderSync(
            http,
            ElderSyncRequest(deviceId = store.deviceId()),
        ).getOrNull() ?: return
        val token = response.elderToken?.trim().orEmpty()
        val sessionId = response.sessionId?.trim().orEmpty()
        val wsUrl = AssistWsUrlResolver.resolve(response.wsUrl, snapshot.serverWsUrl)
        if (token.isBlank() || sessionId.isBlank()) return
        activeToken = token
        updateSnapshot {
            it.copy(
                phase = AssistSessionPhase.ACTIVE,
                sessionId = sessionId,
                peerDisplayName = response.caregiverDisplayName.ifBlank { "家人" },
                statusMessage = "${response.caregiverDisplayName.ifBlank { "家人" }} 已连接",
            )
        }
        callbacks.onAssistModeChanged(true)
        connectWebSocket(wsUrl, token)
    }

    fun setRole(role: AssistRole) {
        store.saveRole(role)
        updateSnapshot { it.copy(role = role) }
        syncElderPoll()
    }

    fun setDisplayName(name: String) {
        store.saveDisplayName(name)
        updateSnapshot { it.copy(displayName = name) }
    }

    fun setServerHttpUrl(url: String) {
        val normalized = AssistEndpointUrls.normalizeHttpBase(url)
        store.saveServerHttpUrl(normalized)
        updateSnapshot { it.copy(serverHttpUrl = normalized) }
        refreshBindings()
    }

    fun setServerWsUrl(url: String) {
        val normalized = AssistEndpointUrls.normalizeWsBase(
            raw = url,
            httpBase = snapshot.serverHttpUrl,
        )
        store.saveServerWsUrl(normalized)
        updateSnapshot { it.copy(serverWsUrl = normalized) }
    }

    fun refreshBindings() {
        val http = snapshot.serverHttpUrl
        if (http.isBlank()) return
        scope.launch {
            val result = apiClient.listBindings(
                http,
                ListBindingsRequest(deviceId = store.deviceId()),
            )
            result.onSuccess { bindings ->
                updateSnapshot { it.copy(bindings = bindings) }
            }
        }
    }

    fun startElderSession() {
        val http = snapshot.serverHttpUrl
        if (http.isBlank()) {
            updateSnapshot { it.copy(statusMessage = "请先填写协助服务器地址") }
            return
        }
        scope.launch {
            val response = apiClient.createPair(
                http,
                CreatePairRequest(
                    deviceId = store.deviceId(),
                    displayName = snapshot.displayName.ifBlank { "老人" },
                ),
            ).getOrElse { error ->
                updateSnapshot {
                    it.copy(statusMessage = AssistConnectionErrors.formatHttpFailure(error, http))
                }
                return@launch
            }
            activeToken = response.elderToken
            updateSnapshot {
                it.copy(
                    phase = AssistSessionPhase.WAITING_PEER,
                    pairCode = response.pairCode,
                    sessionId = response.sessionId,
                    statusMessage = "等待家人连接，协助码 ${response.pairCode}",
                )
            }
            callbacks.onAssistModeChanged(true)
            connectWebSocket(response.wsUrl, response.elderToken)
        }
    }

    fun joinWithPairCode(pairCode: String) {
        val http = AssistEndpointUrls.normalizeHttpBase(snapshot.serverHttpUrl)
        if (http.isBlank()) {
            updateSnapshot { it.copy(statusMessage = "请先填写协助服务器地址") }
            return
        }
        if (http != snapshot.serverHttpUrl) {
            store.saveServerHttpUrl(http)
            updateSnapshot { it.copy(serverHttpUrl = http) }
        }
        scope.launch {
            val response = apiClient.joinPair(
                http,
                JoinPairRequest(
                    pairCode = pairCode.trim(),
                    deviceId = store.deviceId(),
                    displayName = snapshot.displayName.ifBlank { "家人" },
                ),
            ).getOrElse { error ->
                updateSnapshot {
                    it.copy(statusMessage = AssistConnectionErrors.formatHttpFailure(error, http))
                }
                return@launch
            }
            activeToken = response.caregiverToken
            updateSnapshot {
                it.copy(
                    phase = AssistSessionPhase.ACTIVE,
                    sessionId = response.sessionId,
                    peerDisplayName = response.elderDisplayName,
                    statusMessage = "已连接 ${response.elderDisplayName}",
                )
            }
            connectWebSocket(response.wsUrl, response.caregiverToken)
            refreshBindings()
        }
    }

    fun connectBinding(binding: BindingDto) {
        val http = snapshot.serverHttpUrl
        if (http.isBlank()) return
        scope.launch {
            val response = apiClient.connectBinding(
                http,
                ConnectBindingRequest(
                    caregiverDeviceId = store.deviceId(),
                    elderDeviceId = binding.elderDeviceId,
                    caregiverDisplayName = snapshot.displayName.ifBlank { "家人" },
                ),
            ).getOrElse { error ->
                updateSnapshot { it.copy(statusMessage = error.message ?: "绑定连接失败") }
                return@launch
            }
            activeToken = response.caregiverToken
            updateSnapshot {
                it.copy(
                    phase = AssistSessionPhase.ACTIVE,
                    sessionId = response.sessionId,
                    peerDisplayName = response.elderDisplayName,
                    statusMessage = "已通过绑定连接 ${response.elderDisplayName}",
                )
            }
            connectWebSocket(response.wsUrl, response.caregiverToken)
        }
    }

    fun deleteBinding(bindingId: String) {
        val http = snapshot.serverHttpUrl
        if (http.isBlank()) return
        scope.launch {
            apiClient.deleteBinding(
                http,
                DeleteBindingRequest(bindingId = bindingId, deviceId = store.deviceId()),
            )
            refreshBindings()
        }
    }

    fun sendTap(x: Int, y: Int) {
        relayClient.sendControl(AssistControlMessage.tap(x, y))
    }

    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        relayClient.sendControl(AssistControlMessage.swipe(x1, y1, x2, y2))
    }

    fun sendAction(name: String) {
        relayClient.sendControl(AssistControlMessage.action(name))
    }

    fun sendTypeText(text: String) {
        relayClient.sendControl(AssistControlMessage.typeText(text))
    }

    fun sendCommand(text: String) {
        relayClient.sendControl(AssistControlMessage.command(text))
    }

    fun relayAgentStatus(status: String, message: String) {
        if (snapshot.role != AssistRole.ELDER || snapshot.phase != AssistSessionPhase.ACTIVE) return
        relayClient.sendControl(AssistControlMessage.agentStatus(status, message))
    }

    fun endSession() {
        val http = snapshot.serverHttpUrl
        val sessionId = snapshot.sessionId
        if (http.isNotBlank() && sessionId.isNotBlank()) {
            scope.launch {
                apiClient.endPair(
                    http,
                    EndPairRequest(sessionId = sessionId, deviceId = store.deviceId()),
                )
            }
        }
        relayClient.sendControl(AssistControlMessage.hangup())
        cleanupSession("已结束协助")
    }

    override fun onOpen() {
        if (snapshot.role == AssistRole.ELDER && snapshot.phase == AssistSessionPhase.ACTIVE) {
            startFrameLoop()
        }
    }

    override fun onControlMessage(message: AssistControlMessage) {
        when (message.type) {
            AssistControlMessage.TYPE_PEER_JOINED -> {
                updateSnapshot {
                    it.copy(
                        phase = AssistSessionPhase.ACTIVE,
                        peerDisplayName = message.displayName,
                        statusMessage = "${message.displayName} 已连接",
                    )
                }
                if (snapshot.role == AssistRole.ELDER) {
                    startFrameLoop()
                }
            }
            AssistControlMessage.TYPE_SESSION_ENDED -> cleanupSession("协助已结束：${message.reason}")
            AssistControlMessage.TYPE_FRAME_META -> pendingFrameMeta = message
            AssistControlMessage.TYPE_ACTION_RESULT,
            AssistControlMessage.TYPE_AGENT_STATUS,
            -> if (snapshot.role == AssistRole.CAREGIVER) {
                updateSnapshot { it.copy(statusMessage = message.detail.ifBlank { message.message }) }
            }
            AssistControlMessage.TYPE_TAP,
            AssistControlMessage.TYPE_SWIPE,
            AssistControlMessage.TYPE_ACTION,
            AssistControlMessage.TYPE_TYPE_TEXT,
            AssistControlMessage.TYPE_COMMAND,
            -> {
                if (snapshot.role != AssistRole.ELDER) return
                scope.launch {
                    remoteCommandMutex.withLock {
                        val result = commandExecutor.execute(application, message)
                        relayClient.sendControl(result)
                        if (messageUsesGesture(message)) {
                            delay(500)
                        }
                    }
                    if (!isScrollAction(message)) {
                        pushFrame(force = true)
                    }
                }
            }
        }
    }

    override fun onBinaryFrame(bytes: ByteArray) {
        val meta = pendingFrameMeta
        pendingFrameMeta = null
        if (snapshot.role == AssistRole.CAREGIVER) {
            streamStats.onFrameReceived(meta)
            updateSnapshot {
                it.copy(
                    latestFrameBytes = bytes,
                    latestFrameWidth = meta?.w ?: it.latestFrameWidth,
                    latestFrameHeight = meta?.h ?: it.latestFrameHeight,
                    latestFrameFormat = meta?.fmt ?: it.latestFrameFormat,
                    streamFps = streamStats.fps(),
                    streamLatencyMs = streamStats.latencyMs(),
                )
            }
        }
    }

    override fun onClosed(reason: String?) {
        cleanupSession(reason ?: "连接已关闭")
    }

    override fun onFailure(message: String) {
        cleanupSession(AssistConnectionErrors.formatWsFailure(message, snapshot.serverWsUrl, snapshot.serverHttpUrl))
    }

    private fun isScrollAction(message: AssistControlMessage): Boolean =
        message.type == AssistControlMessage.TYPE_SWIPE ||
            (message.type == AssistControlMessage.TYPE_ACTION && message.name in SCROLL_ACTIONS)

    private fun messageUsesGesture(message: AssistControlMessage): Boolean =
        message.type == AssistControlMessage.TYPE_TAP ||
            message.type == AssistControlMessage.TYPE_SWIPE ||
            (message.type == AssistControlMessage.TYPE_ACTION && message.name in SCROLL_ACTIONS)

    private fun connectWebSocket(serverWsUrl: String, token: String) {
        val wsUrl = AssistWsUrlResolver.resolve(
            serverWsUrl = serverWsUrl,
            localWsUrl = snapshot.serverWsUrl,
            localHttpUrl = snapshot.serverHttpUrl,
        )
        relayClient.disconnect()
        relayClient = AssistRelayClient(this)
        relayClient.connect(token, wsUrl)
    }

    private fun startFrameLoop() {
        frameLoopJob?.cancel()
        frameLoopJob = scope.launch {
            while (isActive && snapshot.phase == AssistSessionPhase.ACTIVE) {
                pushFrame(force = false)
                delay(AssistFrameEncoder.FRAME_LOOP_INTERVAL_MS)
            }
        }
    }

    private suspend fun pushFrame(force: Boolean) {
        val service = JoyAccessibilityService.instance ?: return
        VisionOverlayGuard.withHiddenForCapture {
            val frame = AssistFrameEncoder.captureIfNeeded(service, force = force) ?: return@withHiddenForCapture
            val meta = AssistControlMessage.frameMeta(
                seq = frame.seq,
                w = frame.width,
                h = frame.height,
                fmt = frame.format,
            )
            relayClient.sendFrame(meta, frame.bytes)
        }
    }

    private fun cleanupSession(message: String) {
        frameLoopJob?.cancel()
        frameLoopJob = null
        elderPollJob?.cancel()
        elderPollJob = null
        relayClient.disconnect()
        streamStats.reset()
        callbacks.onAssistModeChanged(false)
        updateSnapshot {
            AssistSessionSnapshot(
                role = it.role,
                phase = AssistSessionPhase.ENDED,
                statusMessage = message,
                bindings = it.bindings,
                serverHttpUrl = it.serverHttpUrl,
                serverWsUrl = it.serverWsUrl,
                displayName = it.displayName,
            )
        }
        refreshBindings()
        syncElderPoll()
    }

    private fun updateSnapshot(transform: (AssistSessionSnapshot) -> AssistSessionSnapshot) {
        snapshot = transform(snapshot)
        onSnapshot(snapshot)
    }
}
