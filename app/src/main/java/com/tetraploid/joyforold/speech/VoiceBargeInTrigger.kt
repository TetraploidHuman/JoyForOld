package com.tetraploid.joyforold.speech

/**
 * 判定 TTS 播报期间是否应视为用户开口打断（需连续若干帧高能量人声，并跳过播报起始 grace）。
 */
class VoiceBargeInTrigger(
    private val requiredConsecutiveHits: Int = 3,
    private val gracePeriodMs: Long = 400L,
) {
    private var consecutiveSpeech = 0
    private var startedAtMs = 0L
    private var triggered = false

    fun reset(startedAtMs: Long = System.currentTimeMillis()) {
        this.startedAtMs = startedAtMs
        consecutiveSpeech = 0
        triggered = false
    }

    /** @return true once barge-in has fired (latched). */
    fun onFrame(hasSpeech: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (triggered) return true
        if (nowMs - startedAtMs < gracePeriodMs) return false
        if (hasSpeech) {
            consecutiveSpeech++
            if (consecutiveSpeech >= requiredConsecutiveHits) {
                triggered = true
                return true
            }
        } else {
            consecutiveSpeech = 0
        }
        return false
    }
}
