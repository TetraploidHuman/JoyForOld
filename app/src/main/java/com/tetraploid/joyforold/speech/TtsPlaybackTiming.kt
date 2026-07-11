package com.tetraploid.joyforold.speech

/**
 * Android TTS [UtteranceProgressListener.onDone] fires when synthesis is queued,
 * often before the speaker finishes. Wait this extra tail before opening the mic.
 */
object TtsPlaybackTiming {
    fun playbackTailMs(text: String): Long {
        val chars = text.trim().length
        if (chars == 0) return 0L
        return (chars * 200L).coerceIn(300L, 8_000L)
    }
}
