package com.tetraploid.joyforold.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD gate: only forwards audio to KWS when speech is detected,
 * with a short hangover to avoid clipping wake phrase edges.
 */
class SileroVadGate(
    context: Context,
    private val hangoverMs: Long = 500L,
    threshold: Float = 0.32f,
) {
    private val vad: Vad
    private var speechActive = false
    private var lastSpeechAtMs = 0L

    init {
        val modelPath = SileroVadModelManager(context).ensureReadyBlocking()
        vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = threshold,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.08f,
                    windowSize = 512,
                    maxSpeechDuration = 8f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
    }

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
        val samples = FloatArray(len / 2)
        var idx = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            samples[idx++] = signed / 32768f
            i += 2
        }
        if (idx == 0) return false
        val chunk = if (idx == samples.size) samples else samples.copyOf(idx)
        vad.acceptWaveform(chunk)
        return vad.isSpeechDetected()
    }

    fun release() {
        runCatching { vad.release() }
    }
}
