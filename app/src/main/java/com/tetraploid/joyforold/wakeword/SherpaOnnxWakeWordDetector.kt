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
    secondStageThreshold: Float = defaultSecondStageThreshold(keywordThreshold),
) {
    private val logTag = "SherpaOnnxWakeWord"
    private val appContext = context.applicationContext
    private val modelManager = SherpaOnnxModelManager(appContext)
    private val stage2Threshold = secondStageThreshold.coerceAtLeast(keywordThreshold)
    private val expectedTokenCount = expectedModelingTokenCount(keyword)
    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var verifyKeywordSpotter: KeywordSpotter? = null
    private var verifyStream: OnlineStream? = null
    private var calibratorSpotter: KeywordSpotter? = null
    private var calibratorStream: OnlineStream? = null
    private var calibratorSpotterThreshold: Float = Float.NaN
    private var modelFiles: SherpaOnnxModelManager.ModelFiles? = null
    private var ready = false

    fun isReady(): Boolean = ready

    fun modelHint(): String = modelManager.modelHint()

    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val files = modelManager.ensureReady(keyword, keywordScore, keywordThreshold)
            release()
            modelFiles = files
            keywordSpotter = createSpotter(files, keywordThreshold)
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
        val samples = pcmToFloat(pcm16le, len)
        onlineStream.acceptWaveform(samples, 16000)
        while (spotter.isReady(onlineStream)) {
            spotter.decode(onlineStream)
        }
        val result = spotter.getResult(onlineStream)
        val detected = result.keyword.trim()
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
        val spotter: KeywordSpotter
        val onlineStream: OnlineStream
        when {
            threshold == keywordThreshold -> {
                spotter = keywordSpotter ?: return false
                onlineStream = stream ?: return false
            }
            threshold == stage2Threshold -> {
                val verify = verifySpotterForStage2() ?: return false
                spotter = verify.first
                onlineStream = verify.second
            }
            else -> {
                spotter = calibratorSpotterFor(threshold) ?: return false
                onlineStream = calibratorStream ?: return false
            }
        }
        return runVerify(spotter, onlineStream, pcm16le, len, threshold)
    }

    private fun runVerify(
        spotter: KeywordSpotter,
        onlineStream: OnlineStream,
        pcm16le: ByteArray,
        len: Int,
        threshold: Float,
    ): Boolean {
        spotter.reset(onlineStream)
        val samples = pcmToFloat(pcm16le, len)
        onlineStream.acceptWaveform(samples, 16000)
        while (spotter.isReady(onlineStream)) {
            spotter.decode(onlineStream)
        }
        val result = spotter.getResult(onlineStream)
        val detected = result.keyword.trim()
        if (detected.isBlank()) return false
        if (!matchesKeyword(detected)) return false
        val tokenCount = result.tokens.size
        if (tokenCount < expectedTokenCount) {
            Log.d(
                logTag,
                "second-stage reject: tokens=$tokenCount expected>=$expectedTokenCount threshold=$threshold",
            )
            spotter.reset(onlineStream)
            return false
        }
        Log.d(
            logTag,
            "second-stage hit: $detected tokens=$tokenCount threshold=$threshold",
        )
        spotter.reset(onlineStream)
        return true
    }

    fun release() {
        runCatching { stream?.release() }
        runCatching { verifyStream?.release() }
        runCatching { calibratorStream?.release() }
        runCatching { keywordSpotter?.release() }
        if (verifyKeywordSpotter !== keywordSpotter) {
            runCatching { verifyKeywordSpotter?.release() }
        }
        runCatching { calibratorSpotter?.release() }
        stream = null
        verifyStream = null
        calibratorStream = null
        keywordSpotter = null
        verifyKeywordSpotter = null
        calibratorSpotter = null
        calibratorSpotterThreshold = Float.NaN
        modelFiles = null
        ready = false
    }

    private fun verifySpotterForStage2(): Pair<KeywordSpotter, OnlineStream>? {
        verifyKeywordSpotter?.let { spotter ->
            verifyStream?.let { return spotter to it }
        }
        val files = modelFiles ?: return null
        verifyKeywordSpotter = createSpotter(files, stage2Threshold)
        verifyStream = verifyKeywordSpotter?.createStream("")
        val spotter = verifyKeywordSpotter ?: return null
        val onlineStream = verifyStream ?: return null
        return spotter to onlineStream
    }

    private fun calibratorSpotterFor(threshold: Float): KeywordSpotter? {
        if (threshold == calibratorSpotterThreshold) {
            return calibratorSpotter
        }
        val files = modelFiles ?: return null
        runCatching { calibratorStream?.release() }
        runCatching { calibratorSpotter?.release() }
        calibratorSpotter = createSpotter(files, threshold)
        calibratorStream = calibratorSpotter?.createStream("")
        calibratorSpotterThreshold = threshold
        return calibratorSpotter
    }

    private fun createSpotter(files: SherpaOnnxModelManager.ModelFiles, threshold: Float): KeywordSpotter {
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
            keywordsThreshold = threshold,
            numTrailingBlanks = 2,
        )
        return KeywordSpotter(null, config)
    }

    private fun matchesKeyword(detected: String): Boolean {
        val label = keyword.trim().replace(' ', '_')
        return detected.contains(keyword) ||
            detected.contains("@$label", ignoreCase = true) ||
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
        const val SECOND_STAGE_STRICTNESS_MULTIPLIER = 1.65f

        fun defaultSecondStageThreshold(stage1Threshold: Float): Float =
            (stage1Threshold * SECOND_STAGE_STRICTNESS_MULTIPLIER).coerceAtMost(0.06f)

        private fun expectedModelingTokenCount(keyword: String): Int {
            val lexicon = EnglishPhoneLexicon(null)
            val line = runCatching {
                SherpaKeywordEncoder.encodeKeywordVariants(keyword, lexicon).first()
            }.getOrNull().orEmpty()
            val count = KeywordTokenValidator.modelingTokens(line).size
            return count.coerceAtLeast(1)
        }
    }
}
