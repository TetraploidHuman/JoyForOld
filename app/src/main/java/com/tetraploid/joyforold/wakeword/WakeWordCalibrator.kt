package com.tetraploid.joyforold.wakeword

/**
 * On-device threshold calibration from user-provided positive and negative PCM samples.
 */
class WakeWordCalibrator(
    val detector: SherpaOnnxWakeWordDetector,
) {
    data class Result(
        val recommendedThreshold: Float,
        val recommendedScore: Float,
        val positiveHitRate: Float,
        val negativeHitRate: Float,
    )

    private val positiveSamples = mutableListOf<ByteArray>()
    private var negativeSample: ByteArray? = null

    fun reset() {
        positiveSamples.clear()
        negativeSample = null
    }

    fun positiveCount(): Int = positiveSamples.size

    fun hasNegativeSample(): Boolean = negativeSample != null

    fun addPositiveSample(pcm: ByteArray) {
        if (pcm.isNotEmpty()) positiveSamples += pcm.copyOf()
    }

    fun setNegativeSample(pcm: ByteArray) {
        negativeSample = pcm.copyOf()
    }

    fun calibrate(
        baseScore: Float,
        baseThreshold: Float,
    ): Result? {
        if (positiveSamples.isEmpty() || negativeSample == null) return null

        var bestThreshold = baseThreshold
        var bestScore = baseScore
        var bestPositiveRate = 0f
        var bestNegativeRate = 1f

        val thresholds = generateThresholds(baseThreshold)
        for (threshold in thresholds) {
            val positiveHits = positiveSamples.count { detector.verifyBuffered(it, it.size, threshold) }
            val negativeHits = if (detector.verifyBuffered(negativeSample!!, negativeSample!!.size, threshold)) 1 else 0
            val positiveRate = positiveHits.toFloat() / positiveSamples.size
            val negativeRate = negativeHits.toFloat()
            val better = positiveRate > bestPositiveRate ||
                (positiveRate == bestPositiveRate && negativeRate < bestNegativeRate)
            if (better) {
                bestThreshold = threshold
                bestScore = baseScore
                bestPositiveRate = positiveRate
                bestNegativeRate = negativeRate
            }
        }

        if (bestPositiveRate <= 0f) return null
        return Result(
            recommendedThreshold = bestThreshold,
            recommendedScore = bestScore,
            positiveHitRate = bestPositiveRate,
            negativeHitRate = bestNegativeRate,
        )
    }

    private fun generateThresholds(base: Float): List<Float> {
        val values = mutableListOf<Float>()
        var t = (base * 0.6f).coerceAtLeast(0.006f)
        val max = (base * 1.6f).coerceAtMost(0.06f)
        while (t <= max) {
            values += t
            t += 0.002f
        }
        return values.distinct()
    }
}
