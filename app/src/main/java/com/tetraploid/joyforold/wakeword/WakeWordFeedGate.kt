package com.tetraploid.joyforold.wakeword

/**
 * 仅在检测到语音活动（含 hangover）时向 KWS 送音频，过滤环境底噪误触发。
 */
class WakeWordFeedGate(
    sileroGate: SileroVadGate?,
    rmsGate: SpeechActivityGate?,
    private val hangoverMs: Long = 520L,
) {
    private val rmsActivityGate = rmsGate ?: SpeechActivityGate(hangoverMs = hangoverMs)
    private val sileroGate = sileroGate
    private var speechActiveUntilMs = 0L

    fun shouldFeed(pcm16le: ByteArray, len: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val speech = sileroGate?.hasSpeech(pcm16le, len) == true ||
            rmsActivityGate.hasSpeech(pcm16le, len)
        if (speech) {
            speechActiveUntilMs = nowMs + hangoverMs
            return true
        }
        return nowMs <= speechActiveUntilMs
    }
}
