package com.tetraploid.joyforold.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.max
import com.tetraploid.joyforold.network.JoyHttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class DoubaoAsrClient(
    private val apiKey: String,
    private val appId: String,
    private val accessToken: String,
    private val resourceId: String,
    private val httpClient: HttpClient = JoyHttpClients.websocket(),
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private val wsSessionRef = AtomicReference<DefaultClientWebSocketSession?>(null)
    private var wsJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var streamingJob: Job? = null
    private var connectId: String = ""
    private var lastAudioFrame: ByteArray? = null
    @Volatile
    private var finalText: String = ""
    private val pendingAudioFrames = ArrayDeque<ByteArray>()
    private val pendingAudioLock = Any()
    @Volatile
    private var preRollPcm: ByteArray? = null
    @Volatile
    private var opened = false
    @Volatile
    private var shortUtteranceMode = false
    private var endpointStopTracker: AsrEndpointStopTracker? = null
    @Volatile
    private var errorReported = false
    @Volatile
    private var stopRecordingRequested = false
    @Volatile
    private var sessionDelivered = false
    @Volatile
    private var manualStopRequested = false
    @Volatile
    private var connectionPrepared = false
    @Volatile
    private var clientRequestSent = false
    private var onPartialCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onPrepareReady: (() -> Unit)? = null
    private var onPrepareError: ((String) -> Unit)? = null

    fun setPreRollPcm(pcm: ByteArray?) {
        preRollPcm = pcm?.copyOf()
    }

    fun prepareConnection(
        shortUtterance: Boolean = false,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (streamingJob?.isActive == true) {
            onError("识别会话进行中")
            return
        }
        if (!validateCredentials(onError)) return
        if (connectionPrepared && opened) {
            shortUtteranceMode = shortUtterance
            onReady()
            return
        }
        if (webSocketActive() && !opened) {
            onPrepareReady = onReady
            onPrepareError = onError
            shortUtteranceMode = shortUtterance
            return
        }
        closeWebSocket()
        resetSessionState(shortUtterance)
        onPrepareReady = onReady
        onPrepareError = onError
        openWebSocket(deferClientRequest = true)
    }

    fun cancelPrepare() {
        if (streamingJob?.isActive == true) {
            cancelSession()
            return
        }
        onPrepareReady = null
        onPrepareError = null
        if (!connectionPrepared && !webSocketActive()) return
        cancelSession()
    }

    fun isSessionIdle(): Boolean = streamingJob?.isActive != true && !webSocketActive()

    fun start(
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit,
        /** 短句模式（如语音确认）：说完后更快结束 */
        shortUtterance: Boolean = false,
    ) {
        if (streamingJob?.isActive == true) return
        if (!validateCredentials(onError)) return

        onPartialCallback = onPartialText
        onFinalCallback = onFinalText
        onErrorCallback = onError
        onPrepareReady = null
        onPrepareError = null

        if (connectionPrepared && opened) {
            activatePreparedSession(shortUtterance)
            return
        }

        closeWebSocket()
        resetSessionState(shortUtterance)
        openWebSocket(deferClientRequest = false)
        beginRecordingSession()
    }

    private fun activatePreparedSession(shortUtterance: Boolean) {
        connectionPrepared = false
        shortUtteranceMode = shortUtterance
        endpointStopTracker = createEndpointStopTracker(shortUtterance).also {
            it.reset(System.currentTimeMillis())
        }
        scope.launch {
            val session = wsSessionRef.get()
            if (session != null && !clientRequestSent) {
                sendFullClientRequest(session)
                clientRequestSent = true
                flushPendingAudioFrames()
            }
        }
        beginRecordingSession()
    }

    private fun validateCredentials(onError: (String) -> Unit): Boolean {
        if (apiKey.isBlank() && (appId.isBlank() || accessToken.isBlank())) {
            onError(
                "豆包 ASR 配置缺失：请在 local.properties 设置 volc.asr.api_key，或旧版 volc.asr.app_id / volc.asr.access_token",
            )
            return false
        }
        return true
    }

    private fun resetSessionState(shortUtterance: Boolean) {
        connectId = UUID.randomUUID().toString()
        finalText = ""
        lastAsrErrorDetail = null
        errorReported = false
        stopRecordingRequested = false
        sessionDelivered = false
        manualStopRequested = false
        opened = false
        connectionPrepared = false
        clientRequestSent = false
        shortUtteranceMode = shortUtterance
        endpointStopTracker = createEndpointStopTracker(shortUtterance).also {
            it.reset(System.currentTimeMillis())
        }
        synchronized(pendingAudioLock) { pendingAudioFrames.clear() }
    }

    private fun openWebSocket(deferClientRequest: Boolean) {
        closeWebSocket()
        wsJob = scope.launch {
            try {
                httpClient.webSocket(
                    urlString = ASR_URL,
                    request = {
                        header("X-Api-Resource-Id", resourceId.ifBlank { DEFAULT_RESOURCE_ID })
                        header("X-Api-Connect-Id", connectId)
                        header("X-Api-Request-Id", UUID.randomUUID().toString())
                        header("X-Api-Sequence", "-1")
                        if (apiKey.isNotBlank()) {
                            header("X-Api-Key", apiKey)
                        } else {
                            header("X-Api-App-Key", appId)
                            header("X-Api-Access-Key", accessToken)
                        }
                    },
                ) {
                    wsSessionRef.set(this)
                    opened = true
                    if (deferClientRequest) {
                        connectionPrepared = true
                        onPrepareReady?.invoke()
                        onPrepareReady = null
                        onPrepareError = null
                    } else {
                        sendFullClientRequest(this)
                        clientRequestSent = true
                        flushPendingAudioFrames()
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Binary) {
                                handleServerBinaryFrame(frame.readBytes())
                            }
                        }
                    } finally {
                        wsSessionRef.set(null)
                        opened = false
                        connectionPrepared = false
                        clientRequestSent = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val httpStatus = extractHttpStatus(error)
                if (connectionPrepared && streamingJob == null) {
                    connectionPrepared = false
                    val prepareError = onPrepareError
                    onPrepareError = null
                    onPrepareReady = null
                    prepareError?.invoke(formatConnectionError(error, httpStatus))
                    return@launch
                }
                if (sessionDelivered || finalText.isNotBlank() || errorReported) return@launch
                errorReported = true
                onErrorCallback?.invoke(formatConnectionError(error, httpStatus))
            }
        }
    }

    private fun closeWebSocket() {
        wsJob?.cancel()
        wsJob = null
        wsSessionRef.set(null)
    }

    private fun webSocketActive(): Boolean = wsJob?.isActive == true || wsSessionRef.get() != null

    private fun beginRecordingSession() {
        if (streamingJob?.isActive == true) return
        val onFinalText = onFinalCallback ?: return
        val onError = onErrorCallback ?: return
        streamingJob = scope.launch {
            runCatching {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufferSize = max(minBuffer, SAMPLE_RATE * BYTES_PER_SAMPLE / 2)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    if (!errorReported) {
                        errorReported = true
                        onError("录音设备初始化失败，请检查麦克风权限或是否被其他应用占用")
                    }
                    return@launch
                }
                audioRecord = record
                record.startRecording()

                preRollPcm?.let { preRoll ->
                    preRollPcm = null
                    injectPreRollAsChunks(preRoll)
                }

                val chunk = ByteArray(CHUNK_BYTES_200MS)
                val maxChunks = if (shortUtteranceMode) MAX_RECORD_CHUNKS_SHORT else MAX_RECORD_CHUNKS
                var chunkCount = 0
                while (isActive && !stopRecordingRequested) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        delay(10)
                        continue
                    }
                    chunkCount++
                    if (chunkCount >= maxChunks) break
                    val payload = if (read == chunk.size) chunk else chunk.copyOf(read)
                    if (!opened || !clientRequestSent) {
                        enqueuePendingAudio(payload)
                        continue
                    }

                    sendAudioFrame(payload, isLast = false)

                    val stopAction = endpointStopTracker?.onPartial(
                        text = finalText,
                        definite = false,
                        nowMs = System.currentTimeMillis(),
                    )
                    if (stopAction == EndpointStopAction.Stop || stopRecordingRequested) break
                }

                finalizeSession(onFinalText, onError)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (sessionDelivered || finalText.isNotBlank()) return@onFailure
                if (!errorReported) {
                    errorReported = true
                    onError("录音失败：${error.message ?: "unknown"}")
                }
            }
        }
    }

    private suspend fun finalizeSession(
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (sessionDelivered) return
        releaseRecording()
        if (opened) {
            val last = lastAudioFrame
            if (last != null && last.isNotEmpty()) {
                sendAudioFrame(last, isLast = true)
            }
            delay(FINAL_RESULT_WAIT_MS)
            wsSessionRef.get()?.close(CloseReason(CloseReason.Codes.NORMAL, "auto-stop"))
            closeWebSocket()
            opened = false
            connectionPrepared = false
            clientRequestSent = false
            lastAudioFrame = null
        }
        sessionDelivered = true
        if (manualStopRequested) {
            lastAsrErrorDetail = null
            return
        }
        if (finalText.isBlank() && !lastAsrErrorDetail.isNullOrBlank()) {
            if (!errorReported) errorReported = true
            onError("语音识别失败：$lastAsrErrorDetail")
            lastAsrErrorDetail = null
            return
        }
        lastAsrErrorDetail = null
        onFinalText(finalText)
    }

    private fun parseErrorFrameDetail(raw: ByteArray): String? {
        if (raw.size < 12) return null
        return runCatching {
            val payloadSize = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            if (payloadSize <= 0 || raw.size < 12 + payloadSize) return null
            val payload = raw.copyOfRange(12, 12 + payloadSize)
            val compression = raw[2].toInt() and 0x0F
            val decoded = if (compression == COMPRESSION_GZIP) gunzip(payload) else payload
            val text = String(decoded, Charsets.UTF_8).trim()
            if (text.startsWith("{")) {
                val obj = JSONObject(text)
                obj.optString("message").ifBlank {
                    obj.optJSONObject("error")?.optString("message").orEmpty()
                }.ifBlank { text.take(120) }
            } else {
                text.take(120)
            }
        }.getOrNull()
    }

    suspend fun stop(onFinalText: (String) -> Unit) {
        manualStopRequested = true
        stopRecordingRequested = true
        streamingJob?.join()
        streamingJob = null
        if (!sessionDelivered) {
            releaseRecording()
            if (opened) {
                val last = lastAudioFrame
                if (last != null && last.isNotEmpty()) {
                    sendAudioFrame(last, isLast = true)
                }
                delay(FINAL_RESULT_WAIT_MS)
            }
            wsSessionRef.get()?.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
            closeWebSocket()
            opened = false
            lastAudioFrame = null
            sessionDelivered = true
        }
        lastAsrErrorDetail = null
        onFinalText(finalText)
    }

    fun cancelSession() {
        stopRecordingRequested = true
        streamingJob?.cancel()
        streamingJob = null
        releaseRecording()
        closeWebSocket()
        opened = false
        connectionPrepared = false
        clientRequestSent = false
        lastAudioFrame = null
        errorReported = false
        sessionDelivered = false
        manualStopRequested = false
        onPartialCallback = null
        onFinalCallback = null
        onErrorCallback = null
        onPrepareReady = null
        onPrepareError = null
        synchronized(pendingAudioLock) { pendingAudioFrames.clear() }
    }

    fun shutdown() {
        cancelSession()
        supervisorJob.cancel()
    }

    private fun releaseRecording() {
        val record = audioRecord
        audioRecord = null
        record?.runCatching {
            stop()
            release()
        }
    }

    private suspend fun sendFullClientRequest(session: DefaultClientWebSocketSession) {
        val endWindowSize = if (shortUtteranceMode) END_WINDOW_SIZE_SHORT_MS else END_WINDOW_SIZE_NORMAL_MS
        val forceToSpeechTime = if (shortUtteranceMode) FORCE_TO_SPEECH_SHORT_MS else FORCE_TO_SPEECH_NORMAL_MS
        val body = JSONObject().apply {
            put("user", JSONObject().put("uid", connectId))
            put(
                "audio",
                JSONObject()
                    .put("format", "pcm")
                    .put("codec", "raw")
                    .put("rate", SAMPLE_RATE)
                    .put("bits", 16)
                    .put("channel", 1)
                    .put("language", "zh-CN"),
            )
            put(
                "request",
                JSONObject()
                    .put("model_name", "bigmodel")
                    // 开启二遍识别后，服务端会返回 definite 分句，用于可靠判停。
                    .put("enable_nonstream", true)
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("enable_ddc", false)
                    .put("show_utterances", true)
                    .put("result_type", "full")
                    .put("end_window_size", endWindowSize)
                    .put("force_to_speech_time", forceToSpeechTime),
            )
        }.toString().toByteArray(Charsets.UTF_8)
        val compressed = gzip(body)

        val frame = ByteBuffer
            .allocate(8 + compressed.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_FULL_CLIENT_REQUEST_GZIP_JSON)
            .putInt(compressed.size)
            .put(compressed)
            .array()
        session.send(Frame.Binary(true, frame))
    }

    private suspend fun sendAudioFrame(audio: ByteArray, isLast: Boolean) {
        val session = wsSessionRef.get() ?: return
        if (audio.isNotEmpty()) {
            // 保存最后一帧音频，stop 时可用作 last package，避免空负载。
            lastAudioFrame = audio
        }
        val compressed = gzip(audio)
        val header = if (isLast) {
            HEADER_AUDIO_ONLY_LAST_GZIP_RAW
        } else {
            HEADER_AUDIO_ONLY_GZIP_RAW
        }
        val frame = ByteBuffer
            .allocate(8 + compressed.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(compressed.size)
            .put(compressed)
            .array()
        session.send(Frame.Binary(true, frame))
    }

    @Volatile
    private var lastAsrErrorDetail: String? = null

    private fun enqueuePendingAudio(audio: ByteArray) {
        synchronized(pendingAudioLock) {
            pendingAudioFrames.addLast(audio.copyOf())
            while (pendingAudioFrames.size > MAX_PENDING_AUDIO_FRAMES) {
                pendingAudioFrames.removeFirst()
            }
        }
    }

    private suspend fun injectPreRollAsChunks(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + CHUNK_BYTES_200MS, pcm.size)
            val slice = pcm.copyOfRange(offset, end)
            if (!opened || !clientRequestSent) {
                enqueuePendingAudio(slice)
            } else {
                sendAudioFrame(slice, isLast = false)
            }
            offset = end
        }
    }

    private suspend fun flushPendingAudioFrames() {
        val frames = synchronized(pendingAudioLock) {
            pendingAudioFrames.toList().also { pendingAudioFrames.clear() }
        }
        frames.forEach { frame -> sendAudioFrame(frame, isLast = false) }
    }

    private fun handleServerBinaryFrame(raw: ByteArray) {
        if (raw.size < 8) return
        val second = raw[1].toInt() and 0xFF
        val messageType = (second shr 4) and 0x0F
        val compression = raw[2].toInt() and 0x0F

        if (messageType == MESSAGE_TYPE_ERROR) {
            lastAsrErrorDetail = parseErrorFrameDetail(raw) ?: "服务端返回错误帧"
            return
        }
        if (messageType != MESSAGE_TYPE_FULL_SERVER_RESPONSE) return

        val payloadSize = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (payloadSize <= 0 || raw.size < 12 + payloadSize) return
        val payload = raw.copyOfRange(12, 12 + payloadSize)
        val decoded = if (compression == COMPRESSION_GZIP) gunzip(payload) else payload
        val parsed = runCatching {
            parseAsrResponse(JSONObject(String(decoded, Charsets.UTF_8)))
        }.getOrElse { AsrParseResult("", false) }
        if (parsed.text.isNotBlank()) {
            finalText = parsed.text
            lastAsrErrorDetail = null
            onPartialCallback?.invoke(parsed.text)
        }
        val stopAction = endpointStopTracker?.onPartial(
            text = parsed.text,
            definite = parsed.hasDefiniteUtterance,
            nowMs = System.currentTimeMillis(),
        )
        if (stopAction == EndpointStopAction.Stop) {
            stopRecordingRequested = true
        }
    }

    internal data class AsrParseResult(
        val text: String,
        val hasDefiniteUtterance: Boolean,
    )

    companion object {
        private const val ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val DEFAULT_RESOURCE_ID = "volc.bigasr.sauc.duration"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES_200MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 5
        private const val END_WINDOW_SIZE_NORMAL_MS = 2400
        private const val END_WINDOW_SIZE_SHORT_MS = 1200
        private const val FORCE_TO_SPEECH_NORMAL_MS = 900
        private const val FORCE_TO_SPEECH_SHORT_MS = 450
        private const val END_DEBOUNCE_NORMAL_MS = 900L
        private const val END_DEBOUNCE_SHORT_MS = 550L
        private const val MIN_RECORD_BEFORE_STOP_NORMAL_MS = 450L
        private const val MIN_RECORD_BEFORE_STOP_SHORT_MS = 300L
        private const val POST_ENDPOINT_TAIL_CHUNKS = 2
        private const val MAX_RECORD_CHUNKS = 150
        private const val MAX_RECORD_CHUNKS_SHORT = 50
        private const val FINAL_RESULT_WAIT_MS = 400L
        private const val MAX_PENDING_AUDIO_FRAMES = 40

        internal fun createEndpointStopTracker(shortUtterance: Boolean): AsrEndpointStopTracker {
            return AsrEndpointStopTracker(
                debounceAfterDefiniteMs = if (shortUtterance) END_DEBOUNCE_SHORT_MS else END_DEBOUNCE_NORMAL_MS,
                minRecordBeforeStopMs = if (shortUtterance) MIN_RECORD_BEFORE_STOP_SHORT_MS else MIN_RECORD_BEFORE_STOP_NORMAL_MS,
                tailChunksRequired = POST_ENDPOINT_TAIL_CHUNKS,
            )
        }

        internal fun parseAsrResponse(json: JSONObject): AsrParseResult {
            val result = json.optJSONObject("result") ?: return AsrParseResult("", false)
            var text = ""
            var hasDefinite = false

            val utterances = result.optJSONArray("utterances")
            if (utterances != null && utterances.length() > 0) {
                val parts = buildList {
                    for (i in 0 until utterances.length()) {
                        val utterance = utterances.optJSONObject(i) ?: continue
                        val utteranceText = utterance.optString("text").trim()
                        if (utteranceText.isNotBlank()) add(utteranceText)
                        if (utterance.optBoolean("definite", false) && utteranceText.isNotBlank()) {
                            hasDefinite = true
                        }
                    }
                }
                if (parts.isNotEmpty()) {
                    text = parts.joinToString("")
                }
            }

            if (text.isBlank()) {
                text = result.optString("text").trim()
            }
            return AsrParseResult(text = text, hasDefiniteUtterance = hasDefinite)
        }

        private const val MESSAGE_TYPE_FULL_SERVER_RESPONSE = 0b1001
        private const val MESSAGE_TYPE_ERROR = 0b1111
        private const val COMPRESSION_GZIP = 0b0001

        // version=1, header_size=1, msg_type=1, flags=0, serialization=json, compression=gzip, reserved=0
        private val HEADER_FULL_CLIENT_REQUEST_GZIP_JSON =
            byteArrayOf(0x11, 0x10, 0x11, 0x00)
        // version=1, header_size=1, msg_type=2, flags=0, serialization=none, compression=gzip, reserved=0
        private val HEADER_AUDIO_ONLY_GZIP_RAW =
            byteArrayOf(0x11, 0x20, 0x01, 0x00)
        // version=1, header_size=1, msg_type=2, flags=2(last package), serialization=none, compression=gzip
        private val HEADER_AUDIO_ONLY_LAST_GZIP_RAW =
            byteArrayOf(0x11, 0x22, 0x01, 0x00)

        internal fun formatConnectionError(t: Throwable, httpStatus: Int? = null): String {
            val code = httpStatus
            val msg = t.message.orEmpty()
            if (code == 401 || code == 403) {
                return "豆包 ASR 鉴权失败（HTTP $code），请检查 API Key 与 Resource ID 是否与火山控制台一致"
            }
            if (msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("ENETUNREACH", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true)
            ) {
                return "无法连接豆包语音服务器（openspeech.bytedance.com）。" +
                    "请检查：①手机能否正常上网；②系统设置里是否允许本应用使用 WiFi/流量；" +
                    "③关闭异常 VPN/代理后重试"
            }
            if (msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true)) {
                return "连接豆包语音服务器超时，请检查网络后重试"
            }
            return "语音识别连接失败：HTTP ${code ?: "?"}，${msg.ifBlank { "unknown" }}"
        }

        private fun extractHttpStatus(error: Throwable): Int? {
            val message = error.message.orEmpty()
            Regex("""HTTP (\d{3})""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            Regex("""\((\d{3})\)""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            return null
        }

        private fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        private fun gunzip(bytes: ByteArray): ByteArray {
            return GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }
    }
}
