package com.tetraploid.joyforold.assist.server.routes

import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import com.tetraploid.joyforold.assist.protocol.AssistMessageJson
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.server.auth.JwtConfig
import com.tetraploid.joyforold.assist.server.service.PairingService
import com.tetraploid.joyforold.assist.server.service.RoomManager
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.isActive

fun Application.configureWebSockets(
    roomManager: RoomManager,
    pairingService: PairingService,
) {
    routing {
        webSocket("/ws") {
            val token = call.request.queryParameters["token"]?.trim().orEmpty()
            val claims = JwtConfig.verify(token)
            if (claims == null) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
                return@webSocket
            }

            val sessionInfo = pairingService.getSession(claims.sessionId)
            if (sessionInfo == null || sessionInfo.status == "ENDED") {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "session_ended"))
                return@webSocket
            }

            val room = roomManager.ensureRoom(claims.sessionId)
            room.attach(claims.role, this@webSocket)

            val joinedRole = when (claims.role) {
                AssistRole.ELDER -> "elder"
                AssistRole.CAREGIVER -> "caregiver"
            }
            val joinedDisplayName = pairingService.resolvePeerDisplayName(claims.sessionId, claims.role)
            room.peer(claims.role)?.let { peer ->
                sendPeer(peer, AssistControlMessage.peerJoined(joinedRole, joinedDisplayName))
            }

            try {
                for (frame in incoming) {
                    if (!isActive) break
                    when (frame) {
                        is Frame.Text -> relayText(room, claims.role, frame.readText())
                        is Frame.Binary -> relayBinary(room, claims.role, frame.readBytes())
                        else -> Unit
                    }
                }
            } finally {
                room.detach(claims.role, this)
                room.peer(claims.role)?.let { peer ->
                    sendPeer(peer, AssistControlMessage.sessionEnded("${claims.role.name.lowercase()}_disconnect"))
                }
                if (room.elderSocket == null && room.caregiverSocket == null) {
                    roomManager.removeRoom(claims.sessionId)
                    pairingService.endSession(claims.sessionId, claims.deviceId)
                }
            }
        }
    }
}

private suspend fun relayText(room: com.tetraploid.joyforold.assist.server.service.Room, from: AssistRole, text: String) {
    val peer = room.peer(from) ?: return
    if (AssistMessageJson.typeOf(text) == AssistControlMessage.TYPE_HANGUP) {
        sendPeer(peer, AssistControlMessage.sessionEnded("peer_hangup"))
        peer.close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.NORMAL,
                "hangup",
            ),
        )
        return
    }
    peer.send(Frame.Text(text))
}

private suspend fun relayBinary(room: com.tetraploid.joyforold.assist.server.service.Room, from: AssistRole, bytes: ByteArray) {
    val peer = room.peer(from) ?: return
    peer.send(Frame.Binary(true, bytes))
}

private suspend fun sendPeer(peer: DefaultWebSocketServerSession, message: AssistControlMessage) {
    peer.send(Frame.Text(AssistMessageJson.encode(message)))
}
