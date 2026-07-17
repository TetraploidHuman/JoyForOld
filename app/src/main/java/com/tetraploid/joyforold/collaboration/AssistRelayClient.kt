package com.tetraploid.joyforold.collaboration

import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import com.tetraploid.joyforold.assist.protocol.AssistMessageJson
import com.tetraploid.joyforold.network.JoyHttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

class AssistRelayClient(
    private val listener: Listener,
    private val client: HttpClient = JoyHttpClients.websocket(),
) {
    interface Listener {
        fun onOpen()
        fun onControlMessage(message: AssistControlMessage)
        fun onBinaryFrame(bytes: ByteArray)
        fun onClosed(reason: String?)
        fun onFailure(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRef = AtomicReference<DefaultClientWebSocketSession?>(null)
    private var connectJob: Job? = null

    fun connect(token: String, wsBaseUrl: String) {
        disconnect()
        connectJob = scope.launch {
            try {
                val wsUrl = AssistEndpointUrls.normalizeWsBase(
                    raw = wsBaseUrl,
                    httpBase = "",
                    default = wsBaseUrl,
                )
                val url = "$wsUrl?token=${java.net.URLEncoder.encode(token, Charsets.UTF_8.name())}"
                client.webSocket(urlString = url) {
                    sessionRef.set(this)
                    listener.onOpen()
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> AssistMessageJson.decode(frame.readText())?.let(listener::onControlMessage)
                                is Frame.Binary -> listener.onBinaryFrame(frame.readBytes())
                                else -> Unit
                            }
                        }
                        listener.onClosed(null)
                    } finally {
                        sessionRef.set(null)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                listener.onFailure(error.message ?: "WebSocket 连接失败")
            }
        }
    }

    fun sendControl(message: AssistControlMessage): Boolean {
        val session = sessionRef.get() ?: return false
        return runBlocking {
            runCatching {
                session.send(Frame.Text(AssistMessageJson.encode(message)))
                true
            }.getOrDefault(false)
        }
    }

    fun sendFrame(meta: AssistControlMessage, bytes: ByteArray): Boolean {
        val session = sessionRef.get() ?: return false
        return runBlocking {
            runCatching {
                session.send(Frame.Text(AssistMessageJson.encode(meta)))
                session.send(Frame.Binary(true, bytes))
                true
            }.getOrDefault(false)
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        runBlocking {
            sessionRef.getAndSet(null)?.close(CloseReason(CloseReason.Codes.NORMAL, "client_disconnect"))
        }
    }
}
