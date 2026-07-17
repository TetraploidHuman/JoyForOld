package com.tetraploid.joyforold.collaboration

import com.tetraploid.joyforold.assist.protocol.AssistHttpJson
import com.tetraploid.joyforold.assist.protocol.BindingDto
import com.tetraploid.joyforold.assist.protocol.ConnectBindingRequest
import com.tetraploid.joyforold.assist.protocol.ConnectBindingResponse
import com.tetraploid.joyforold.assist.protocol.CreatePairRequest
import com.tetraploid.joyforold.assist.protocol.CreatePairResponse
import com.tetraploid.joyforold.assist.protocol.DeleteBindingRequest
import com.tetraploid.joyforold.assist.protocol.DeleteBindingResponse
import com.tetraploid.joyforold.assist.protocol.ElderSyncRequest
import com.tetraploid.joyforold.assist.protocol.ElderSyncResponse
import com.tetraploid.joyforold.assist.protocol.EndPairRequest
import com.tetraploid.joyforold.assist.protocol.EndPairResponse
import com.tetraploid.joyforold.assist.protocol.ErrorResponse
import com.tetraploid.joyforold.assist.protocol.JoinPairRequest
import com.tetraploid.joyforold.assist.protocol.JoinPairResponse
import com.tetraploid.joyforold.assist.protocol.ListBindingsRequest
import com.tetraploid.joyforold.assist.protocol.ListBindingsResponse
import com.tetraploid.joyforold.network.JoyHttpClients
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking

class AssistApiClient(
    private val httpClient: HttpClient = JoyHttpClients.default(),
) {
    fun createPair(baseUrl: String, request: CreatePairRequest): Result<CreatePairResponse> =
        post("$baseUrl/api/v1/pair/create", request)

    fun joinPair(baseUrl: String, request: JoinPairRequest): Result<JoinPairResponse> =
        post("$baseUrl/api/v1/pair/join", request)

    fun endPair(baseUrl: String, request: EndPairRequest): Result<EndPairResponse> =
        post("$baseUrl/api/v1/pair/end", request)

    fun listBindings(baseUrl: String, request: ListBindingsRequest): Result<List<BindingDto>> =
        post<ListBindingsRequest, ListBindingsResponse>("$baseUrl/api/v1/bindings/list", request).map { it.bindings }

    fun connectBinding(baseUrl: String, request: ConnectBindingRequest): Result<ConnectBindingResponse> =
        post("$baseUrl/api/v1/bindings/connect", request)

    fun deleteBinding(baseUrl: String, request: DeleteBindingRequest): Result<DeleteBindingResponse> =
        post("$baseUrl/api/v1/bindings/delete", request)

    fun elderSync(baseUrl: String, request: ElderSyncRequest): Result<ElderSyncResponse> =
        post("$baseUrl/api/v1/pair/elder-sync", request)

    fun pingHealth(baseUrl: String): Result<Boolean> = runCatching {
        runBlocking {
            val requestUrl = normalizeApiUrl("$baseUrl/api/v1/health")
            val response = httpClient.get(requestUrl)
            response.status.isSuccess()
        }
    }

    private inline fun <reified Req : Any, reified Res : Any> post(url: String, body: Req): Result<Res> = runCatching {
        runBlocking {
            val requestUrl = normalizeApiUrl(url)
            val payload = AssistHttpJson.encode(body)
            val response = httpClient.post(requestUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val error = runCatching { AssistHttpJson.decode<ErrorResponse>(text) }.getOrNull()?.error
                error("HTTP ${response.status.value}: ${error ?: text}")
            }
            AssistHttpJson.decode(text)
        }
    }

    private fun normalizeApiUrl(fullUrl: String): String {
        val trimmed = fullUrl.trim()
        val apiIndex = trimmed.indexOf("/api/")
        return if (apiIndex >= 0) {
            val base = AssistEndpointUrls.normalizeHttpBase(trimmed.substring(0, apiIndex))
            if (base.isBlank()) error("协助服务器 HTTP 地址未配置")
            "$base${trimmed.substring(apiIndex)}"
        } else {
            val base = AssistEndpointUrls.normalizeHttpBase(trimmed)
            if (base.isBlank()) error("协助服务器 HTTP 地址未配置")
            base
        }
    }
}
