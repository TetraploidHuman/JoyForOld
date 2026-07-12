package com.tetraploid.joyforold.assist.server.service

import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.assist.server.db.FamilyBindings
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class BindingInfo(
    val id: Uuid,
    val elderDeviceId: Uuid,
    val caregiverDeviceId: Uuid,
    val elderDisplayName: String,
    val caregiverDisplayName: String,
    val createdAt: Long,
)

class BindingService {
    fun ensureBinding(
        elderDeviceId: Uuid,
        caregiverDeviceId: Uuid,
        elderDisplayName: String,
        caregiverDisplayName: String,
    ) {
        transaction {
            val existing = FamilyBindings.selectAll()
                .where {
                    (FamilyBindings.elderDeviceId eq elderDeviceId) and
                        (FamilyBindings.caregiverDeviceId eq caregiverDeviceId)
                }
                .singleOrNull()
            if (existing == null) {
                FamilyBindings.insert {
                    it[id] = Uuid.random()
                    it[FamilyBindings.elderDeviceId] = elderDeviceId
                    it[FamilyBindings.caregiverDeviceId] = caregiverDeviceId
                    it[FamilyBindings.elderDisplayName] = elderDisplayName
                    it[FamilyBindings.caregiverDisplayName] = caregiverDisplayName
                    it[FamilyBindings.createdAt] = Clock.System.now()
                }
            } else {
                FamilyBindings.update({ FamilyBindings.id eq existing[FamilyBindings.id].value }) {
                    it[FamilyBindings.elderDisplayName] = elderDisplayName
                    it[FamilyBindings.caregiverDisplayName] = caregiverDisplayName
                }
            }
        }
    }

    fun listBindings(deviceId: Uuid): List<BindingDto> = transaction {
        FamilyBindings.selectAll()
            .where {
                (FamilyBindings.elderDeviceId eq deviceId) or
                    (FamilyBindings.caregiverDeviceId eq deviceId)
            }
            .map { row ->
                BindingDto(
                    id = row[FamilyBindings.id].value.toString(),
                    elderDeviceId = row[FamilyBindings.elderDeviceId].toString(),
                    caregiverDeviceId = row[FamilyBindings.caregiverDeviceId].toString(),
                    elderDisplayName = row[FamilyBindings.elderDisplayName],
                    caregiverDisplayName = row[FamilyBindings.caregiverDisplayName],
                    createdAt = row[FamilyBindings.createdAt].toEpochMilliseconds(),
                )
            }
    }

    fun findBinding(elderDeviceId: Uuid, caregiverDeviceId: Uuid): BindingInfo? = transaction {
        FamilyBindings.selectAll()
            .where {
                (FamilyBindings.elderDeviceId eq elderDeviceId) and
                    (FamilyBindings.caregiverDeviceId eq caregiverDeviceId)
            }
            .singleOrNull()
            ?.let { row ->
                BindingInfo(
                    id = row[FamilyBindings.id].value,
                    elderDeviceId = row[FamilyBindings.elderDeviceId],
                    caregiverDeviceId = row[FamilyBindings.caregiverDeviceId],
                    elderDisplayName = row[FamilyBindings.elderDisplayName],
                    caregiverDisplayName = row[FamilyBindings.caregiverDisplayName],
                    createdAt = row[FamilyBindings.createdAt].toEpochMilliseconds(),
                )
            }
    }

    fun deleteBinding(bindingId: Uuid, deviceId: Uuid): Boolean = transaction {
        val deleted = FamilyBindings.deleteWhere {
            (FamilyBindings.id eq bindingId) and
                ((FamilyBindings.elderDeviceId eq deviceId) or (FamilyBindings.caregiverDeviceId eq deviceId))
        }
        deleted > 0
    }
}
