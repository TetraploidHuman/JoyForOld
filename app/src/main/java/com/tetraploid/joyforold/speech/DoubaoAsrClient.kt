package com.tetraploid.joyforold.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.max
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class DoubaoAsrClient(
    private val apiKey: String,
    private val appId: String,
    private val accessToken: String,
    private val resourceId: String,
    private val okHttpClient: OkHttpClient = sharedClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var streamingJob: Job? = null
    private var connectId: String = ""
    private var lastAudioFrame: ByteArray? = null
    private var finalText: String = ""
    private val pendingAudioFrames = ArrayDeque<ByteArray>()
    private val pendingAudioLock = Any()
    @Volatile
    private var opened = false
    @Volatile
    private var shortUtteranceMode = false
    private var endpointStopTracker: AsrEndpointStopTracker? = null
    @Volatile
    private var errorReported = false

    fun start(
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit,
        /** 短句模式（如语音确认）：说完后更快结束 */
        shortUtterance: Boolean = false,
    ) {
        if (streamingJob?.isActive == true) return
        if (apiKey.isBlank() && (appId.isBlank() || accessToken.isBlank())) {
            onError(
                "豆包 ASR 配置缺失：请在 local.properties 设置 volc.asr.api_key，或旧版 volc.asr.app_id / volc.asr.access_token",
            )
            return
        }

        connectId = UUID.randomUUID().toString()
        finalText = ""
        lastAsrErrorDetail = null
        errorReported = false
        opened = false
        shortUtteranceMode = shortUtterance
        endpointStopTracker = createEndpointStopTracker(shortUtterance).also {
            it.reset(System.currentTimeMillis())
        }
        synchronized(pendingAudioLock) { pendingAudioFrames.clear() }

        val requestBuilder = Request.Builder()
            .url(ASR_URL)
            .addHeader("X-Api-Resource-Id", resourceId.ifBlank { DEFAULT_RESOURCE_ID })
            .addHeader("X-Api-Connect-Id", connectId)
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .addHeader("X-Api-Sequence", "-1")
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("X-Api-Key", apiKey)
        } else {
            requestBuilder
                .addHeader("X-Api-App-Key", appId)
                .addHeader("X-Api-Access-Key", accessToken)
        }
        val request = requestBuilder.build()

        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    sendFullClientRequest(webSocket)
                    flushPendingAudioFrames()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerBinaryFrame(bytes.toByteArray(), onPartialText)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // OkHttp 可能在正常 close 或网络抖动时触发 onFailure。
                    // 若本轮已经拿到识别文本，就不要再对 UI 报“失败”，避免误导。
                    if (finalText.isNotBlank() || errorReported) return
                    errorReported = true
                    onError(formatConnectionError(t, response))
                }
            },
        )

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
                audioRecord = record
                record.startRecording()

                val chunk = ByteArray(CHUNK_BYTES_200MS)
                val maxChunks = if (shortUtterance) MAX_RECORD_CHUNKS_SHORT else MAX_RECORD_CHUNKS
                var chunkCount = 0
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    chunkCount++
                    if (chunkCount >= maxChunks) break
                    val payload = if (read == chunk.size) chunk else chunk.copyOf(read)
                    if (!opened) {
                        enqueuePendingAudio(payload)
                        continue
                    }

                    sendAudioFrame(payload, isLast = false)

                    val stopAction = endpointStopTracker?.onPartial(
                        text = finalText,
                        definite = false,
                        nowMs = System.currentTimeMillis(),
                    )
                    if (stopAction == EndpointStopAction.Stop) break
                }

                finalizeSession(onFinalText, onError)
            }.onFailure {
                onError("录音失败：${it.message ?: "unknown"}")
            }
        }
    }

    private suspend fun finalizeSession(
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        releaseRecording()
        if (opened) {
            val last = lastAudioFrame
            if (last != null && last.isNotEmpty()) {
                sendAudioFrame(last, isLast = true)
            }
            delay(FINAL_RESULT_WAIT_MS)
            webSocket?.close(1000, "auto-stop")
            webSocket = null
            opened = false
            lastAudioFrame = null
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
        streamingJob?.cancelAndJoin()
        streamingJob = null
        releaseRecording()

        if (opened) {
            val last = lastAudioFrame
            if (last != null && last.isNotEmpty()) {
                sendAudioFrame(last, isLast = true)
            }
            delay(FINAL_RESULT_WAIT_MS)
        }
        webSocket?.close(1000, "done")
        webSocket = null
        opened = false
        lastAudioFrame = null
        onFinalText(finalText)
    }

    private fun releaseRecording() {
        val record = audioRecord
        audioRecord = null
        record?.runCatching {
            stop()
            release()
        }
    }

    private fun sendFullClientRequest(socket: WebSocket) {
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
        socket.send(ByteString.of(*frame))
    }

    private fun sendAudioFrame(audio: ByteArray, isLast: Boolean) {
        val socket = webSocket ?: return
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
        socket.send(ByteString.of(*frame))
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

    private fun flushPendingAudioFrames() {
        val frames = synchronized(pendingAudioLock) {
            pendingAudioFrames.toList().also { pendingAudioFrames.clear() }
        }
        frames.forEach { frame -> sendAudioFrame(frame, isLast = false) }
    }

    private fun handleServerBinaryFrame(
        raw: ByteArray,
        onPartialText: (String) -> Unit,
    ) {
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
            onPartialText(parsed.text)
        }
        val stopAction = endpointStopTracker?.onPartial(
            text = parsed.text,
            definite = parsed.hasDefiniteUtterance,
            nowMs = System.currentTimeMillis(),
        )
        if (stopAction == EndpointStopAction.Stop) {
            streamingJob?.cancel()
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
        private const val END_WINDOW_SIZE_NORMAL_MS = 4800
        private const val END_WINDOW_SIZE_SHORT_MS = 1800
        private const val FORCE_TO_SPEECH_NORMAL_MS = 1600
        private const val FORCE_TO_SPEECH_SHORT_MS = 700
        private const val END_DEBOUNCE_NORMAL_MS = 2600L
        private const val END_DEBOUNCE_SHORT_MS = 1100L
        private const val MIN_RECORD_BEFORE_STOP_NORMAL_MS = 1800L
        private const val MIN_RECORD_BEFORE_STOP_SHORT_MS = 800L
        private const val POST_ENDPOINT_TAIL_CHUNKS = 6
        private const val MAX_RECORD_CHUNKS = 150
        private const val MAX_RECORD_CHUNKS_SHORT = 50
        private const val FINAL_RESULT_WAIT_MS = 900L
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

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        internal fun formatConnectionError(t: Throwable, response: Response?): String {
            val code = response?.code
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

        private fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        private fun gunzip(bytes: ByteArray): ByteArray {
            return GZIPInputStream(bytes.inputStream()).readBytes()
        }
    }
}
