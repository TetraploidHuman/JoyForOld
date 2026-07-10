package com.tetraploid.joyforold.wakeword

import kotlin.math.sqrt

/**
 * 语音活动检测 + 挂起（hangover）：仅在有人说话时把音频送给 KWS，
 * 并在话音结束后多送一小段，避免截断唤醒词开头。
 */
class SpeechActivityGate(
    private val rmsThreshold: Double = 450.0,
    private val hangoverMs: Long = 320L,
) {
    private var speechActive = false
    private var lastSpeechAtMs = 0L

    fun shouldProcess(pcm16le: ByteArray, len: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (hasSpeech(pcm16le, len)) {
            speechActive = true
            lastSpeechAtMs = nowMs
            return true
        }
        if (speechActive && nowMs - lastSpeechAtMs <= hangoverMs) {
            return true
        }
        speechActive = false
        return false
    }

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
