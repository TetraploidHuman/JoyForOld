package com.tetraploid.joyforold.assist.server

import com.tetraploid.joyforold.assist.server.auth.JwtConfig
import com.tetraploid.joyforold.assist.server.db.DatabaseFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class HealthRouteTest {
    @Test
    fun healthEndpointReturnsOk() = testApplication {
        DatabaseFactory.init("jdbc:h2:mem:health_${Uuid.random()};DB_CLOSE_DELAY=-1")
        JwtConfig.init(secret = "test-secret")
        application { module(publicWsUrl = "ws://127.0.0.1/ws") }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\":true"))
    }
}
