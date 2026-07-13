package com.tetraploid.joyforold.assist.server.service

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
