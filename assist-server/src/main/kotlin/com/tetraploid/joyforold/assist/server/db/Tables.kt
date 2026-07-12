package com.tetraploid.joyforold.assist.server.db

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

object Devices : UuidTable("devices") {
    val displayName = varchar("display_name", 64)
    val role = varchar("role", 16)
    val createdAt = timestamp("created_at")
}

object PairSessions : UuidTable("pair_sessions") {
    val pairCode = varchar("pair_code", 6).nullable()
    val elderDeviceId = uuid("elder_device_id")
    val caregiverDeviceId = uuid("caregiver_device_id").nullable()
    val status = varchar("status", 16)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val endedAt = timestamp("ended_at").nullable()
}

object FamilyBindings : UuidTable("family_bindings") {
    val elderDeviceId = uuid("elder_device_id")
    val caregiverDeviceId = uuid("caregiver_device_id")
    val elderDisplayName = varchar("elder_display_name", 64)
    val caregiverDisplayName = varchar("caregiver_display_name", 64)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(elderDeviceId, caregiverDeviceId)
    }
}
