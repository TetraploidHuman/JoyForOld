package com.tetraploid.joyforold.wakeword

import kotlin.math.sqrt

/**
 * 轻量 VAD：仅用 RMS 门控静音片段，降低常听耗电。
 */
class SimpleVadGate(
    private val rmsThreshold: Double = 500.0,
) {
    fun hasSpeech(pcm16le: ByteArray, len: Int): Boolean {
        if (len < 2) return false
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
        if (samples == 0) return false
        return sqrt(sumSquares / samples) >= rmsThreshold
    }
}

