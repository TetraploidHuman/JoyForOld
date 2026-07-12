package com.tetraploid.joyforold.assist.server

import com.tetraploid.joyforold.assist.server.auth.JwtConfig
import com.tetraploid.joyforold.assist.server.db.DatabaseFactory
import com.tetraploid.joyforold.assist.server.routes.configureRoutes
import com.tetraploid.joyforold.assist.server.routes.configureWebSockets
import com.tetraploid.joyforold.assist.server.service.BindingService
import com.tetraploid.joyforold.assist.server.service.PairingService
import com.tetraploid.joyforold.assist.server.service.RoomManager
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val jwtSecret = System.getenv("JWT_SECRET") ?: "joyforold-dev-secret-change-me"
    val databaseUrl = System.getenv("DATABASE_URL")
        ?: "jdbc:h2:file:./data/assist;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE"
    val publicWsUrl = System.getenv("PUBLIC_WS_URL") ?: "ws://127.0.0.1:$port/ws"

    DatabaseFactory.init(databaseUrl)
    JwtConfig.init(secret = jwtSecret)

    embeddedServer(Netty, host = host, port = port) {
        module(publicWsUrl)
    }.start(wait = true)
}

fun Application.module(publicWsUrl: String) {
    val bindingService = BindingService()
    val pairingService = PairingService(bindingService)
    val roomManager = RoomManager()
    pairingService.cleanupExpiredSessions()

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText("error: ${cause.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
        }
    }
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 120.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    configureRoutes(pairingService, bindingService, roomManager, publicWsUrl)
    configureWebSockets(roomManager, pairingService)
}
