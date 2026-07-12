package com.tetraploid.joyforold.assist.server

import com.tetraploid.joyforold.assist.server.db.DatabaseFactory
import com.tetraploid.joyforold.assist.server.db.PairSessions
import com.tetraploid.joyforold.assist.server.service.BindingService
import com.tetraploid.joyforold.assist.server.service.PairingService
import com.tetraploid.joyforold.assist.server.service.SessionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PairingServiceTest {
    private lateinit var pairingService: PairingService

    @BeforeTest
    fun setUp() {
        DatabaseFactory.init("jdbc:h2:mem:assist_test_${Uuid.random()};DB_CLOSE_DELAY=-1")
        pairingService = PairingService(BindingService(), pairCodeTtlMinutes = 10)
    }

    @Test
    fun cleanupExpiredSessionsEndsStaleRows() {
        val elderId = Uuid.random()
        val sessionId = Uuid.random()
        val expiredAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 60_000)
        transaction {
            PairSessions.insert {
                it[id] = sessionId
                it[PairSessions.pairCode] = "123456"
                it[PairSessions.elderDeviceId] = elderId
                it[PairSessions.caregiverDeviceId] = null
                it[PairSessions.status] = SessionStatus.WAITING.name
                it[PairSessions.expiresAt] = expiredAt
                it[PairSessions.createdAt] = expiredAt
                it[PairSessions.endedAt] = null
            }
        }

        val cleaned = pairingService.cleanupExpiredSessions()
        assertTrue(cleaned >= 1)

        val status = transaction {
            PairSessions.selectAll()
                .where { PairSessions.id eq sessionId }
                .single()[PairSessions.status]
        }
        assertEquals(SessionStatus.ENDED.name, status)
    }
}
