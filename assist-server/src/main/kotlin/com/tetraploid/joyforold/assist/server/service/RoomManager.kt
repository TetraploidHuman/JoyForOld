package com.tetraploid.joyforold.assist.server.service

import com.tetraploid.joyforold.assist.protocol.AssistRole
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap

class RoomManager {
    private val rooms = ConcurrentHashMap<Uuid, Room>()

    fun ensureRoom(sessionId: Uuid): Room = rooms.computeIfAbsent(sessionId) { Room(it) }

    fun getRoom(sessionId: Uuid): Room? = rooms[sessionId]

    fun removeRoom(sessionId: Uuid) {
        rooms.remove(sessionId)
    }
}

class Room(val sessionId: Uuid) {
    @Volatile
    var elderSocket: DefaultWebSocketServerSession? = null

    @Volatile
    var caregiverSocket: DefaultWebSocketServerSession? = null

    fun peer(role: AssistRole): DefaultWebSocketServerSession? = when (role) {
        AssistRole.ELDER -> caregiverSocket
        AssistRole.CAREGIVER -> elderSocket
    }

    suspend fun attach(role: AssistRole, session: DefaultWebSocketServerSession) {
        when (role) {
            AssistRole.ELDER -> {
                elderSocket?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
                elderSocket = session
            }
            AssistRole.CAREGIVER -> {
                caregiverSocket?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
                caregiverSocket = session
            }
        }
    }

    fun detach(role: AssistRole, session: DefaultWebSocketServerSession) {
        when (role) {
            AssistRole.ELDER -> if (elderSocket === session) elderSocket = null
            AssistRole.CAREGIVER -> if (caregiverSocket === session) caregiverSocket = null
        }
    }
}
