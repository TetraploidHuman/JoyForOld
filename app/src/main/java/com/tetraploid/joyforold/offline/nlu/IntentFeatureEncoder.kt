package com.tetraploid.joyforold.offline.nlu

import com.tetraploid.joyforold.preset.PresetTextNormalizer
import kotlin.math.sqrt

/**
 * 与 [tools/nlu/train_and_export.py] 对齐的字符 n-gram 特征编码（FNV-1a 哈希）。
 */
object IntentFeatureEncoder {
    const val DEFAULT_FEATURE_DIM = 8192
    const val DEFAULT_MIN_NGRAM = 2
    const val DEFAULT_MAX_NGRAM = 4

    fun encode(
        text: String,
        featureDim: Int = DEFAULT_FEATURE_DIM,
        minNgram: Int = DEFAULT_MIN_NGRAM,
        maxNgram: Int = DEFAULT_MAX_NGRAM,
    ): FloatArray {
        val normalized = normalize(text)
        val features = FloatArray(featureDim)
        if (normalized.isEmpty()) return features

        for (n in minNgram..maxNgram) {
            if (normalized.length < n) continue
            for (i in 0..normalized.length - n) {
                val gram = normalized.substring(i, i + n)
                val bucket = (fnv1a32(gram).toUInt() % featureDim.toUInt()).toInt()
                features[bucket] += 1f
            }
        }
        l2Normalize(features)
        return features
    }

    internal fun normalize(text: String): String {
        return PresetTextNormalizer.normalize(text)
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，,。；;：:！!？?]"), "")
    }

    internal fun fnv1a32(text: String): Int {
        var hash = 2166136261L
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return hash.toInt()
    }

    private fun l2Normalize(features: FloatArray) {
        var sum = 0f
        for (value in features) sum += value * value
        if (sum <= 0f) return
        val inv = 1f / sqrt(sum)
        for (i in features.indices) {
            features[i] *= inv
        }
    }
}
