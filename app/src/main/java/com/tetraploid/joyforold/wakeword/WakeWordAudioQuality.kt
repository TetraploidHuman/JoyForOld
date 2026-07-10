package com.tetraploid.joyforold.wakeword

import kotlin.math.sqrt

object WakeWordAudioQuality {
    private const val MIN_POSITIVE_RMS = 0.012f
    private const val MIN_POSITIVE_BYTES = 16000 * 2 * 600 / 1000

    fun isUsablePositiveSample(pcm: ByteArray): Boolean {
        if (pcm.size < MIN_POSITIVE_BYTES) return false
        return rms(pcm) >= MIN_POSITIVE_RMS
    }

    fun rms(pcm16le: ByteArray): Float {
        if (pcm16le.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            val normalized = signed / 32768.0
            sum += normalized * normalized
            count++
            i += 2
        }
        if (count == 0) return 0f
        return sqrt(sum / count).toFloat()
    }
}
