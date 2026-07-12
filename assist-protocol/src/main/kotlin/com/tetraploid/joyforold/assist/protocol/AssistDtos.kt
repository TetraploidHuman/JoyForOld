package com.tetraploid.joyforold.assist.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class AssistRole {
    ELDER,
    CAREGIVER,
}

@Serializable
data class CreatePairRequest(
    val deviceId: String,
    val displayName: String = "",
)

@Serializable
data class CreatePairResponse(
    val sessionId: String,
    val pairCode: String,
    val elderToken: String,
    val expiresAt: Long,
    val wsUrl: String,
)

@Serializable
data class JoinPairRequest(
    val pairCode: String,
    val deviceId: String,
    val displayName: String = "",
)

@Serializable
data class JoinPairResponse(
    val sessionId: String,
    val caregiverToken: String,
    val elderDisplayName: String,
    val wsUrl: String,
)

@Serializable
data class EndPairRequest(
    val sessionId: String,
    val deviceId: String,
)

@Serializable
data class EndPairResponse(
    val ended: Boolean,
)

@Serializable
data class BindingDto(
    val id: String,
    val elderDeviceId: String,
    val caregiverDeviceId: String,
    val elderDisplayName: String,
    val caregiverDisplayName: String,
    val createdAt: Long,
)

@Serializable
data class ListBindingsRequest(
    val deviceId: String,
)

@Serializable
data class ListBindingsResponse(
    val bindings: List<BindingDto>,
)

@Serializable
data class ConnectBindingRequest(
    val caregiverDeviceId: String,
    val elderDeviceId: String,
    val caregiverDisplayName: String = "",
)

@Serializable
data class ConnectBindingResponse(
    val sessionId: String,
    val elderToken: String,
    val caregiverToken: String,
    val elderDisplayName: String,
    val wsUrl: String,
)

@Serializable
data class DeleteBindingRequest(
    val bindingId: String,
    val deviceId: String,
)

@Serializable
data class DeleteBindingResponse(
    val deleted: Boolean,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String = "joyforold-assist-server",
)

@Serializable
data class ElderSyncRequest(
    val deviceId: String,
)

@Serializable
data class ElderSyncResponse(
    val sessionId: String? = null,
    val elderToken: String? = null,
    val wsUrl: String? = null,
    val caregiverDisplayName: String = "",
)
