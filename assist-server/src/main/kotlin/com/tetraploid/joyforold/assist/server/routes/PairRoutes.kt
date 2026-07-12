package com.tetraploid.joyforold.assist.server.routes

import com.tetraploid.joyforold.assist.protocol.ConnectBindingRequest
import com.tetraploid.joyforold.assist.protocol.ConnectBindingResponse
import com.tetraploid.joyforold.assist.protocol.CreatePairRequest
import com.tetraploid.joyforold.assist.protocol.CreatePairResponse
import com.tetraploid.joyforold.assist.protocol.DeleteBindingRequest
import com.tetraploid.joyforold.assist.protocol.DeleteBindingResponse
import com.tetraploid.joyforold.assist.protocol.EndPairRequest
import com.tetraploid.joyforold.assist.protocol.EndPairResponse
import com.tetraploid.joyforold.assist.protocol.ElderSyncRequest
import com.tetraploid.joyforold.assist.protocol.ElderSyncResponse
import com.tetraploid.joyforold.assist.protocol.ErrorResponse
import com.tetraploid.joyforold.assist.protocol.HealthResponse
import com.tetraploid.joyforold.assist.protocol.JoinPairRequest
import com.tetraploid.joyforold.assist.protocol.JoinPairResponse
import com.tetraploid.joyforold.assist.protocol.ListBindingsRequest
import com.tetraploid.joyforold.assist.protocol.ListBindingsResponse
import com.tetraploid.joyforold.assist.server.service.PairingService
import com.tetraploid.joyforold.assist.server.service.BindingService
import com.tetraploid.joyforold.assist.server.service.RoomManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.uuid.Uuid

fun Application.configureRoutes(
    pairingService: PairingService,
    bindingService: BindingService,
    roomManager: RoomManager,
    publicWsUrl: String,
) {
    routing {
        get("/") {
            call.respondText("JoyForOld Assist Server OK. Health: /api/v1/health")
        }
        get("/health") {
            call.respond(HealthResponse(ok = true))
        }
        get("/api/v1/health") {
            call.respond(HealthResponse(ok = true))
        }

        route("/api/v1/pair") {
            post("/create") {
                val request = call.receive<CreatePairRequest>()
                val result = runCatching {
                    pairingService.createPair(
                        deviceId = request.deviceId,
                        displayName = request.displayName,
                        wsUrl = publicWsUrl,
                    )
                }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = it.message ?: "创建配对失败"))
                    return@post
                }
                call.respond(
                    CreatePairResponse(
                        sessionId = result.sessionId.toString(),
                        pairCode = result.pairCode,
                        elderToken = result.elderToken,
                        expiresAt = result.expiresAt,
                        wsUrl = result.wsUrl,
                    ),
                )
            }

            post("/join") {
                val request = call.receive<JoinPairRequest>()
                val result = pairingService.joinPair(
                    pairCode = request.pairCode.trim(),
                    deviceId = request.deviceId,
                    displayName = request.displayName,
                    wsUrl = publicWsUrl,
                )
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "协助码无效或已过期"))
                    return@post
                }
                call.respond(
                    JoinPairResponse(
                        sessionId = result.sessionId.toString(),
                        caregiverToken = result.caregiverToken,
                        elderDisplayName = result.elderDisplayName,
                        wsUrl = result.wsUrl,
                    ),
                )
            }

            post("/end") {
                val request = call.receive<EndPairRequest>()
                val sessionId = runCatching { Uuid.parse(request.sessionId) }.getOrNull()
                val deviceId = runCatching { Uuid.parse(request.deviceId) }.getOrNull()
                if (sessionId == null || deviceId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "参数无效"))
                    return@post
                }
                val ended = pairingService.endSession(sessionId, deviceId)
                call.respond(EndPairResponse(ended = ended))
            }

            post("/elder-sync") {
                val request = call.receive<ElderSyncRequest>()
                val deviceId = runCatching { Uuid.parse(request.deviceId) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "deviceId 无效"))
                        return@post
                    }
                val result = pairingService.pollElderSession(deviceId, publicWsUrl, roomManager)
                call.respond(
                    ElderSyncResponse(
                        sessionId = result?.sessionId?.toString(),
                        elderToken = result?.elderToken,
                        wsUrl = result?.wsUrl,
                        caregiverDisplayName = result?.caregiverDisplayName.orEmpty(),
                    ),
                )
            }
        }

        route("/api/v1/bindings") {
            post("/list") {
                val request = call.receive<ListBindingsRequest>()
                val deviceId = runCatching { Uuid.parse(request.deviceId) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "deviceId 无效"))
                        return@post
                    }
                call.respond(ListBindingsResponse(bindings = bindingService.listBindings(deviceId)))
            }

            post("/connect") {
                val request = call.receive<ConnectBindingRequest>()
                val result = runCatching {
                    pairingService.connectViaBinding(
                        caregiverDeviceIdRaw = request.caregiverDeviceId,
                        elderDeviceIdRaw = request.elderDeviceId,
                        caregiverDisplayName = request.caregiverDisplayName,
                        wsUrl = publicWsUrl,
                    )
                }.getOrNull()
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "未找到绑定关系"))
                    return@post
                }
                call.respond(
                    ConnectBindingResponse(
                        sessionId = result.sessionId.toString(),
                        elderToken = result.elderToken,
                        caregiverToken = result.caregiverToken,
                        elderDisplayName = result.elderDisplayName,
                        wsUrl = result.wsUrl,
                    ),
                )
            }

            post("/delete") {
                val request = call.receive<DeleteBindingRequest>()
                val bindingId = runCatching { Uuid.parse(request.bindingId) }.getOrNull()
                val deviceId = runCatching { Uuid.parse(request.deviceId) }.getOrNull()
                if (bindingId == null || deviceId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "参数无效"))
                    return@post
                }
                val deleted = bindingService.deleteBinding(bindingId, deviceId)
                call.respond(DeleteBindingResponse(deleted = deleted))
            }
        }
    }
}
