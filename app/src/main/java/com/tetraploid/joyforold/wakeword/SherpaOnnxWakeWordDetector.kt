package com.tetraploid.joyforold.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SherpaOnnxWakeWordDetector(
    context: Context,
    private val keyword: String,
    private val keywordScore: Float,
    private val keywordThreshold: Float,
) {
    private val logTag = "SherpaOnnxWakeWord"
    private val appContext = context.applicationContext
    private val modelManager = SherpaOnnxModelManager(appContext)
    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var ready = false

    fun isReady(): Boolean = ready

    fun modelHint(): String = modelManager.modelHint()

    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val files = modelManager.ensureReady(keyword, keywordScore, keywordThreshold)
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = files.encoder.absolutePath,
                    decoder = files.decoder.absolutePath,
                    joiner = files.joiner.absolutePath,
                ),
                tokens = files.tokens.absolutePath,
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
                modelConfig = modelConfig,
                maxActivePaths = 16,
                keywordsFile = files.keywords.absolutePath,
                keywordsScore = keywordScore,
                keywordsThreshold = keywordThreshold,
                numTrailingBlanks = 2,
            )
            release()
            // 使用绝对路径加载文件时，assetManager 必须为 null，否则 sherpa-onnx 会直接 abort。
            keywordSpotter = KeywordSpotter(null, config)
            stream = keywordSpotter?.createStream("")
            ready = keywordSpotter != null && stream != null
            ready
        }.onFailure {
            Log.e(logTag, "prepare failed: ${it.message}", it)
            release()
        }.getOrDefault(false)
    }

    fun feed(pcm16le: ByteArray, len: Int): Boolean {
        if (!ready) return false
        val spotter = keywordSpotter ?: return false
        val onlineStream = stream ?: return false
        val samples = FloatArray(len / 2)
        var idx = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            samples[idx++] = signed / 32768f
            i += 2
        }
        onlineStream.acceptWaveform(samples, 16000)
        while (spotter.isReady(onlineStream)) {
            spotter.decode(onlineStream)
        }
        val result = spotter.getResult(onlineStream)
        val detected = result.keyword?.trim().orEmpty()
        if (detected.isBlank()) return false
        // 只要命中当前唤醒词（含变体行里的 @标签）即触发。
        val hit = detected.contains(keyword) ||
            detected.contains("@$keyword", ignoreCase = true) ||
            detected.equals(keyword, ignoreCase = true)
        if (hit) {
            Log.d(logTag, "wake hit: $detected")
            spotter.reset(onlineStream)
            return true
        }
        Log.d(logTag, "wake miss: $detected (expect $keyword)")
        return false
    }

    fun release() {
        runCatching { stream?.release() }
        runCatching { keywordSpotter?.release() }
        stream = null
        keywordSpotter = null
        ready = false
    }
}
