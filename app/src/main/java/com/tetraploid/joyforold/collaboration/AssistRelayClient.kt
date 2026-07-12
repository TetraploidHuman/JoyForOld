package com.tetraploid.joyforold.collaboration

import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import com.tetraploid.joyforold.assist.protocol.AssistMessageJson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AssistRelayClient(
    private val listener: Listener,
    private val client: OkHttpClient = sharedClient,
) {
    interface Listener {
        fun onOpen()
        fun onControlMessage(message: AssistControlMessage)
        fun onBinaryFrame(bytes: ByteArray)
        fun onClosed(reason: String?)
        fun onFailure(message: String)
    }

    private val socketRef = AtomicReference<WebSocket?>(null)

    fun connect(token: String, wsBaseUrl: String) {
        disconnect()
        val wsUrl = AssistEndpointUrls.normalizeWsBase(
            raw = wsBaseUrl,
            httpBase = "",
            default = wsBaseUrl,
        )
        val request = Request.Builder()
            .url("$wsUrl?token=${java.net.URLEncoder.encode(token, Charsets.UTF_8.name())}")
            .build()
        socketRef.set(
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        listener.onOpen()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        AssistMessageJson.decode(text)?.let(listener::onControlMessage)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        listener.onBinaryFrame(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        listener.onClosed(reason.ifBlank { null })
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        listener.onFailure(t.message ?: "WebSocket 连接失败")
                    }
                },
            ),
        )
    }

    fun sendControl(message: AssistControlMessage): Boolean =
        socketRef.get()?.send(AssistMessageJson.encode(message)) == true

    fun sendFrame(meta: AssistControlMessage, bytes: ByteArray): Boolean {
        val socket = socketRef.get() ?: return false
        return socket.send(AssistMessageJson.encode(meta)) && socket.send(ByteString.of(*bytes))
    }

    fun disconnect() {
        socketRef.getAndSet(null)?.close(1000, "client_disconnect")
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
