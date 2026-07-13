package com.tetraploid.joyforold.collaboration

import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.protocol.BindingDto

enum class AssistSessionPhase {
    IDLE,
    WAITING_PEER,
    ACTIVE,
    ENDED,
}

data class AssistSessionSnapshot(
    val phase: AssistSessionPhase = AssistSessionPhase.IDLE,
    val role: AssistRole = AssistRole.ELDER,
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AssistSessionSnapshot
        return phase == other.phase &&
            role == other.role &&
            pairCode == other.pairCode &&
            sessionId == other.sessionId &&
            statusMessage == other.statusMessage &&
            peerDisplayName == other.peerDisplayName &&
            latestFrameBytes.contentEquals(other.latestFrameBytes) &&
            latestFrameWidth == other.latestFrameWidth &&
            latestFrameHeight == other.latestFrameHeight &&
            latestFrameFormat == other.latestFrameFormat &&
            bindings == other.bindings &&
            serverHttpUrl == other.serverHttpUrl &&
            serverWsUrl == other.serverWsUrl &&
            displayName == other.displayName &&
            streamFps == other.streamFps &&
            streamLatencyMs == other.streamLatencyMs
    }

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + pairCode.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + statusMessage.hashCode()
        result = 31 * result + peerDisplayName.hashCode()
        result = 31 * result + (latestFrameBytes?.contentHashCode() ?: 0)
        result = 31 * result + latestFrameWidth
        result = 31 * result + latestFrameHeight
        result = 31 * result + latestFrameFormat.hashCode()
        result = 31 * result + bindings.hashCode()
        result = 31 * result + serverHttpUrl.hashCode()
        result = 31 * result + serverWsUrl.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + streamFps.hashCode()
        result = 31 * result + streamLatencyMs.hashCode()
        return result
    }
}
