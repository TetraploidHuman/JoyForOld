package com.tetraploid.joyforold.speech

/**
 * Android TTS [UtteranceProgressListener.onDone] fires when synthesis is queued,
 * often before the speaker finishes. Wait this extra tail before opening the mic.
 */
object TtsPlaybackTiming {
    /** Android TTS onDone 常早于扬声器播完；短提示用更小 tail，避免开麦前空等。 */
    fun playbackTailMs(text: String): Long {
        val chars = text.trim().length
        if (chars == 0) return 0L
        return if (chars <= 16) {
            (chars * 70L).coerceIn(80L, 600L)
        } else {
            (chars * 100L).coerceIn(200L, 2_500L)
        }
    }
}
