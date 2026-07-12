package com.tetraploid.joyforold.assist.server.service

import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.assist.server.auth.JwtConfig
import com.tetraploid.joyforold.assist.server.db.Devices
import com.tetraploid.joyforold.assist.server.db.PairSessions
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class PairingService(
    private val bindingService: BindingService,
    private val pairCodeTtlMinutes: Long = 10,
) {
    fun createPair(deviceId: String, displayName: String, wsUrl: String): CreatePairResult {
        cleanupExpiredSessions()
        val elderDeviceId = parseUuid(deviceId)
        val sessionId = Uuid.random()
        val pairCode = generatePairCode()
        val now = nowInstant()
        val expiresAt = now.plusMillis(pairCodeTtlMinutes * 60_000)

        transaction {
            upsertDevice(elderDeviceId, displayName, AssistRole.ELDER)
            PairSessions.insert {
                it[id] = sessionId
                it[PairSessions.pairCode] = pairCode
                it[PairSessions.elderDeviceId] = elderDeviceId
                it[PairSessions.caregiverDeviceId] = null
                it[PairSessions.status] = SessionStatus.WAITING.name
                it[PairSessions.expiresAt] = expiresAt
                it[PairSessions.createdAt] = now
                it[PairSessions.endedAt] = null
            }
        }

        val token = JwtConfig.issueToken(sessionId, elderDeviceId, AssistRole.ELDER)
        return CreatePairResult(
            sessionId = sessionId,
            pairCode = pairCode,
            elderToken = token,
            expiresAt = expiresAt.toEpochMilliseconds(),
            wsUrl = wsUrl,
        )
    }

    fun joinPair(pairCode: String, deviceId: String, displayName: String, wsUrl: String): JoinPairResult? {
        cleanupExpiredSessions()
        val caregiverDeviceId = parseUuid(deviceId)
        val now = nowInstant()

        val row = transaction {
            PairSessions.selectAll()
                .where {
                    (PairSessions.pairCode eq pairCode) and
                        (PairSessions.status eq SessionStatus.WAITING.name)
                }
                .singleOrNull()
        } ?: return null

        if (row[PairSessions.expiresAt] < now) return null

        val sessionId = row[PairSessions.id].value
        val elderDeviceId = row[PairSessions.elderDeviceId]

        transaction {
            upsertDevice(caregiverDeviceId, displayName, AssistRole.CAREGIVER)
            PairSessions.update({ PairSessions.id eq sessionId }) {
                it[PairSessions.caregiverDeviceId] = caregiverDeviceId
                it[PairSessions.status] = SessionStatus.ACTIVE.name
            }
        }

        val elderDisplayName = transaction {
            Devices.selectAll().where { Devices.id eq elderDeviceId }.singleOrNull()
                ?.get(Devices.displayName).orEmpty()
        }.ifBlank { "老人" }

        bindingService.ensureBinding(
            elderDeviceId = elderDeviceId,
            caregiverDeviceId = caregiverDeviceId,
            elderDisplayName = elderDisplayName,
            caregiverDisplayName = displayName.ifBlank { "家人" },
        )

        val caregiverToken = JwtConfig.issueToken(sessionId, caregiverDeviceId, AssistRole.CAREGIVER)
        return JoinPairResult(
            sessionId = sessionId,
            caregiverToken = caregiverToken,
            elderDisplayName = elderDisplayName,
            wsUrl = wsUrl,
        )
    }

    fun connectViaBinding(
        caregiverDeviceIdRaw: String,
        elderDeviceIdRaw: String,
        caregiverDisplayName: String,
        wsUrl: String,
    ): ConnectBindingResult? {
        cleanupExpiredSessions()
        val caregiverDeviceId = parseUuid(caregiverDeviceIdRaw)
        val elderDeviceId = parseUuid(elderDeviceIdRaw)

        val binding = bindingService.findBinding(elderDeviceId, caregiverDeviceId) ?: return null
        val sessionId = Uuid.random()
        val now = nowInstant()
        val expiresAt = now.plusMillis(pairCodeTtlMinutes * 60_000)

        transaction {
            upsertDevice(caregiverDeviceId, caregiverDisplayName, AssistRole.CAREGIVER)
            PairSessions.insert {
                it[id] = sessionId
                it[PairSessions.pairCode] = null
                it[PairSessions.elderDeviceId] = elderDeviceId
                it[PairSessions.caregiverDeviceId] = caregiverDeviceId
                it[PairSessions.status] = SessionStatus.ACTIVE.name
                it[PairSessions.expiresAt] = expiresAt
                it[PairSessions.createdAt] = now
                it[PairSessions.endedAt] = null
            }
        }

        val elderToken = JwtConfig.issueToken(sessionId, elderDeviceId, AssistRole.ELDER)
        val caregiverToken = JwtConfig.issueToken(sessionId, caregiverDeviceId, AssistRole.CAREGIVER)
        return ConnectBindingResult(
            sessionId = sessionId,
            elderToken = elderToken,
            caregiverToken = caregiverToken,
            elderDisplayName = binding.elderDisplayName,
            wsUrl = wsUrl,
        )
    }

    fun endSession(sessionId: Uuid, deviceId: Uuid): Boolean = transaction {
        val row = PairSessions.selectAll().where { PairSessions.id eq sessionId }.singleOrNull()
            ?: return@transaction false
        if (row[PairSessions.elderDeviceId] != deviceId && row[PairSessions.caregiverDeviceId] != deviceId) {
            return@transaction false
        }
        PairSessions.update({ PairSessions.id eq sessionId }) {
            it[PairSessions.status] = SessionStatus.ENDED.name
            it[PairSessions.endedAt] = nowInstant()
        }
        true
    }

    fun getSession(sessionId: Uuid): SessionInfo? = transaction {
        PairSessions.selectAll().where { PairSessions.id eq sessionId }.singleOrNull()?.let { row ->
            SessionInfo(
                sessionId = row[PairSessions.id].value,
                elderDeviceId = row[PairSessions.elderDeviceId],
                caregiverDeviceId = row[PairSessions.caregiverDeviceId],
                status = row[PairSessions.status],
            )
        }
    }

    fun pollElderSession(elderDeviceId: Uuid, wsUrl: String, roomManager: RoomManager): ElderSyncResult? {
        cleanupExpiredSessions()
        val row = transaction {
            PairSessions.selectAll()
                .where {
                    (PairSessions.elderDeviceId eq elderDeviceId) and
                        (PairSessions.status eq SessionStatus.ACTIVE.name)
                }
                .map { it }
                .maxByOrNull { it[PairSessions.createdAt] }
        } ?: return null
        if (row[PairSessions.caregiverDeviceId] == null) return null
        val sessionId = row[PairSessions.id].value
        if (roomManager.getRoom(sessionId)?.elderSocket != null) return null
        val caregiverName = transaction {
            row[PairSessions.caregiverDeviceId]?.let { caregiverId ->
                Devices.selectAll().where { Devices.id eq caregiverId }.singleOrNull()
                    ?.get(Devices.displayName)
            }.orEmpty()
        }.ifBlank { "家人" }
        return ElderSyncResult(
            sessionId = sessionId,
            elderToken = JwtConfig.issueToken(sessionId, elderDeviceId, AssistRole.ELDER),
            wsUrl = wsUrl,
            caregiverDisplayName = caregiverName,
        )
    }

    fun cleanupExpiredSessions(): Int = transaction {
        val now = nowInstant()
        PairSessions.update({
            (PairSessions.status neq SessionStatus.ENDED.name) and (PairSessions.expiresAt less now)
        }) {
            it[PairSessions.status] = SessionStatus.ENDED.name
            it[PairSessions.endedAt] = now
        }
    }

    fun resolvePeerDisplayName(sessionId: Uuid, joiningRole: AssistRole): String = transaction {
        val row = PairSessions.selectAll().where { PairSessions.id eq sessionId }.singleOrNull()
            ?: return@transaction defaultPeerName(joiningRole)
        when (joiningRole) {
            AssistRole.ELDER -> {
                Devices.selectAll().where { Devices.id eq row[PairSessions.elderDeviceId] }.singleOrNull()
                    ?.get(Devices.displayName).orEmpty().ifBlank { defaultPeerName(joiningRole) }
            }
            AssistRole.CAREGIVER -> {
                row[PairSessions.caregiverDeviceId]?.let { caregiverId ->
                    Devices.selectAll().where { Devices.id eq caregiverId }.singleOrNull()
                        ?.get(Devices.displayName)
                }.orEmpty().ifBlank { defaultPeerName(joiningRole) }
            }
        }
    }

    private fun defaultPeerName(role: AssistRole): String = when (role) {
        AssistRole.ELDER -> "老人"
        AssistRole.CAREGIVER -> "家人"
    }

    private fun upsertDevice(deviceId: Uuid, displayName: String, role: AssistRole) {
        val existing = Devices.selectAll().where { Devices.id eq deviceId }.singleOrNull()
        if (existing == null) {
            Devices.insert {
                it[id] = deviceId
                it[Devices.displayName] = displayName
                it[Devices.role] = role.name
                it[Devices.createdAt] = nowInstant()
            }
        } else if (displayName.isNotBlank()) {
            Devices.update({ Devices.id eq deviceId }) {
                it[Devices.displayName] = displayName
                it[Devices.role] = role.name
            }
        }
    }

    private fun generatePairCode(): String {
        repeat(20) {
            val code = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
            val exists = transaction {
                PairSessions.selectAll()
                    .where {
                        (PairSessions.pairCode eq code) and
                            (PairSessions.status eq SessionStatus.WAITING.name)
                    }
                    .any()
            }
            if (!exists) return code
        }
        error("无法生成唯一协助码")
    }

    private fun parseUuid(raw: String): Uuid =
        runCatching { Uuid.parse(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("deviceId 必须是 UUID")
        }

    private fun nowInstant(): Instant = Clock.System.now()

    private fun Instant.plusMillis(delta: Long): Instant =
        Instant.fromEpochMilliseconds(this.toEpochMilliseconds() + delta)
}

enum class SessionStatus {
    WAITING,
    ACTIVE,
    ENDED,
}

data class CreatePairResult(
    val sessionId: Uuid,
    val pairCode: String,
    val elderToken: String,
    val expiresAt: Long,
    val wsUrl: String,
)

data class JoinPairResult(
    val sessionId: Uuid,
    val caregiverToken: String,
    val elderDisplayName: String,
    val wsUrl: String,
)

data class ConnectBindingResult(
    val sessionId: Uuid,
    val elderToken: String,
    val caregiverToken: String,
    val elderDisplayName: String,
    val wsUrl: String,
)

data class SessionInfo(
    val sessionId: Uuid,
    val elderDeviceId: Uuid,
    val caregiverDeviceId: Uuid?,
    val status: String,
)

data class ElderSyncResult(
    val sessionId: Uuid,
    val elderToken: String,
    val wsUrl: String,
    val caregiverDisplayName: String,
)
