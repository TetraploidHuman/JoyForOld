package com.tetraploid.joyforold.assist.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.tetraploid.joyforold.assist.protocol.AssistRole
import java.util.Date
import kotlin.uuid.Uuid

data class AssistTokenClaims(
    val sessionId: Uuid,
    val deviceId: Uuid,
    val role: AssistRole,
)

object JwtConfig {
    private lateinit var algorithm: Algorithm
    private lateinit var issuer: String
    private var sessionTtlMs: Long = 3_600_000L

    fun init(secret: String, issuer: String = "joyforold-assist", sessionTtlMs: Long = 3_600_000L) {
        this.algorithm = Algorithm.HMAC256(secret)
        this.issuer = issuer
        this.sessionTtlMs = sessionTtlMs
    }

    fun issueToken(sessionId: Uuid, deviceId: Uuid, role: AssistRole): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(issuer)
            .withClaim("sessionId", sessionId.toString())
            .withClaim("deviceId", deviceId.toString())
            .withClaim("role", role.name)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + sessionTtlMs))
            .sign(algorithm)
    }

    fun verify(token: String): AssistTokenClaims? = runCatching {
        val verifier = JWT.require(algorithm).withIssuer(issuer).build()
        val decoded = verifier.verify(token)
        AssistTokenClaims(
            sessionId = Uuid.parse(decoded.getClaim("sessionId").asString()),
            deviceId = Uuid.parse(decoded.getClaim("deviceId").asString()),
            role = AssistRole.valueOf(decoded.getClaim("role").asString()),
        )
    }.getOrNull()
}
