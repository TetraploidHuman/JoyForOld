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
import kotlin.math.sqrt
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
import kotlin.math.max

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
    @Volatile
    private var opened = false

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
        opened = false

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
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerBinaryFrame(bytes.toByteArray(), onPartialText, onError)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val msg = t.message ?: "unknown"
                    onError("语音识别连接失败：HTTP ${code ?: "?"}，${msg}")
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
                var silenceChunks = 0
                var heardSpeech = false
                val silenceLimit = if (shortUtterance) AUTO_STOP_SILENCE_CHUNKS_SHORT else AUTO_STOP_SILENCE_CHUNKS
                val maxChunks = if (shortUtterance) MAX_RECORD_CHUNKS_SHORT else MAX_RECORD_CHUNKS
                var chunkCount = 0
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    chunkCount++
                    if (chunkCount >= maxChunks) break
                    if (!opened) continue
                    val payload = if (read == chunk.size) chunk else chunk.copyOf(read)

                    if (chunkHasSpeech(payload)) {
                        heardSpeech = true
                        silenceChunks = 0
                    } else if (heardSpeech) {
                        silenceChunks++
                        if (silenceChunks >= silenceLimit) break
                    }

                    sendAudioFrame(payload, isLast = false)
                }

                finalizeSession(onFinalText)
            }.onFailure {
                onError("录音失败：${it.message ?: "unknown"}")
            }
        }
    }

    private suspend fun finalizeSession(onFinalText: (String) -> Unit) {
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
        onFinalText(finalText)
    }

    private fun chunkHasSpeech(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < payload.size) {
            val sample = (payload[i].toInt() and 0xFF) or (payload[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed * signed.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return false
        return sqrt(sumSquares / samples) > SPEECH_RMS_THRESHOLD
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
                    // bigmodel_async 需要 enable_nonstream；seed streaming200 对应 ssd_version="200"
                    .put("enable_nonstream", true)
                    .put("ssd_version", "200")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("enable_ddc", false)
                    .put("result_type", "full"),
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

    private fun handleServerBinaryFrame(
        raw: ByteArray,
        onPartialText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (raw.size < 8) return
        val second = raw[1].toInt() and 0xFF
        val messageType = (second shr 4) and 0x0F
        val compression = raw[2].toInt() and 0x0F

        if (messageType == MESSAGE_TYPE_ERROR) {
            onError("豆包 ASR 返回错误帧")
            return
        }
        if (messageType != MESSAGE_TYPE_FULL_SERVER_RESPONSE) return

        val payloadSize = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (payloadSize <= 0 || raw.size < 12 + payloadSize) return
        val payload = raw.copyOfRange(12, 12 + payloadSize)
        val decoded = if (compression == COMPRESSION_GZIP) gunzip(payload) else payload
        val text = runCatching { extractTextFromResponse(JSONObject(String(decoded, Charsets.UTF_8))) }
            .getOrElse { "" }
        if (text.isNotBlank()) {
            finalText = text
            onPartialText(text)
        }
    }

    private fun extractTextFromResponse(json: JSONObject): String {
        return json.optJSONObject("result")
            ?.optString("text")
            ?.trim()
            .orEmpty()
    }

    companion object {
        private const val ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val DEFAULT_RESOURCE_ID = "volc.bigasr.sauc.duration"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES_200MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 5
        private const val AUTO_STOP_SILENCE_CHUNKS = 4
        private const val AUTO_STOP_SILENCE_CHUNKS_SHORT = 3
        private const val MAX_RECORD_CHUNKS = 150
        private const val MAX_RECORD_CHUNKS_SHORT = 50
        private const val SPEECH_RMS_THRESHOLD = 450.0
        private const val FINAL_RESULT_WAIT_MS = 700L

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
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
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
