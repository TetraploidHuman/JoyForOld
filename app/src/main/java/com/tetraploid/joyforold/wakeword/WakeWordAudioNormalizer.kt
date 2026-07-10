package com.tetraploid.joyforold.wakeword

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 对偏小声输入做轻量增益，提升 KWS 召回。 */
object WakeWordAudioNormalizer {
    fun boostIfQuiet(
        pcm16le: ByteArray,
        len: Int,
        targetRms: Double = 1800.0,
        maxGain: Double = 5.0,
    ): Int {
        if (len < 2) return len
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed * signed.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return len
        val rms = sqrt(sumSquares / samples)
        if (rms >= targetRms || rms < 80.0) return len

        val gain = (targetRms / rms).coerceAtMost(maxGain)
        i = 0
        while (i + 1 < len) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            val boosted = (signed * gain).roundToInt().coerceIn(-32768, 32767)
            pcm16le[i] = (boosted and 0xFF).toByte()
            pcm16le[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
        return len
    }
}
