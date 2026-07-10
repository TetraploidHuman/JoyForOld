package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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
    private var verifyKeywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var verifyStream: OnlineStream? = null
    private var stage2Threshold: Float = defaultSecondStageThreshold(keywordThreshold)
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
            stage2Threshold = defaultSecondStageThreshold(keywordThreshold)
            val verifyConfig = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
                modelConfig = modelConfig,
                maxActivePaths = 16,
                keywordsFile = files.keywords.absolutePath,
                keywordsScore = keywordScore,
                keywordsThreshold = stage2Threshold,
                numTrailingBlanks = 2,
            )
            release()
            keywordSpotter = KeywordSpotter(null, config)
            verifyKeywordSpotter = KeywordSpotter(null, verifyConfig)
            stream = keywordSpotter?.createStream("")
            verifyStream = verifyKeywordSpotter?.createStream("")
            ready = keywordSpotter != null &&
                verifyKeywordSpotter != null &&
                stream != null &&
                verifyStream != null
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
        val samples = pcmToFloat(pcm16le, len)
        onlineStream.acceptWaveform(samples, 16000)
        while (spotter.isReady(onlineStream)) {
            spotter.decode(onlineStream)
        }
        val result = spotter.getResult(onlineStream)
        val detected = result.keyword?.trim().orEmpty()
        if (detected.isBlank()) return false
        val hit = matchesKeyword(detected)
        if (hit) {
            Log.d(logTag, "wake candidate: $detected")
            spotter.reset(onlineStream)
            return true
        }
        Log.d(logTag, "wake miss: $detected (expect $keyword)")
        return false
    }

    fun verifyBuffered(
        pcm16le: ByteArray,
        len: Int,
        threshold: Float = stage2Threshold,
    ): Boolean {
        if (!ready) return false
        val spotter = verifyKeywordSpotter ?: return false
        val onlineStream = verifyStream ?: return false
        spotter.reset(onlineStream)
        val samples = pcmToFloat(pcm16le, len)
        onlineStream.acceptWaveform(samples, 16000)
        while (spotter.isReady(onlineStream)) {
            spotter.decode(onlineStream)
        }
        val result = spotter.getResult(onlineStream)
        val detected = result.keyword?.trim().orEmpty()
        if (detected.isBlank()) return false
        val hit = matchesKeyword(detected)
        if (!hit) return false
        val tokenCount = result.tokens?.size ?: 0
        val hasTiming = result.timestamps?.isNotEmpty() == true
        Log.d(
            logTag,
            "second-stage hit: $detected tokens=$tokenCount timing=$hasTiming threshold=$threshold",
        )
        spotter.reset(onlineStream)
        return true
    }

    fun release() {
        runCatching { stream?.release() }
        runCatching { verifyStream?.release() }
        runCatching { keywordSpotter?.release() }
        runCatching { verifyKeywordSpotter?.release() }
        stream = null
        verifyStream = null
        keywordSpotter = null
        verifyKeywordSpotter = null
        ready = false
    }

    private fun matchesKeyword(detected: String): Boolean {
        return detected.contains(keyword) ||
            detected.contains("@$keyword", ignoreCase = true) ||
            detected.equals(keyword, ignoreCase = true)
    }

    private fun pcmToFloat(pcm16le: ByteArray, len: Int): FloatArray {
        val samples = FloatArray(len / 2)
        var idx = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            samples[idx++] = signed / 32768f
            i += 2
        }
        return if (idx == samples.size) samples else samples.copyOf(idx)
    }

    companion object {
        const val SECOND_STAGE_THRESHOLD_RATIO = 1.08f

        fun defaultSecondStageThreshold(stage1Threshold: Float): Float =
            (stage1Threshold * SECOND_STAGE_THRESHOLD_RATIO).coerceAtMost(5f)
    }
}
