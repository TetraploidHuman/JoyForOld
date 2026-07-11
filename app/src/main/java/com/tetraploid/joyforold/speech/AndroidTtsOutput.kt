package com.tetraploid.joyforold.speech

import android.content.Context
import com.tetraploid.joyforold.speech.api.TtsOutput

class AndroidTtsOutput(context: Context) : TtsOutput {
    private val speaker = JoyTtsSpeaker(context)

    override fun speak(text: String, flush: Boolean) {
        speaker.speak(text, flush)
    }

    override suspend fun speakAndAwait(text: String, flush: Boolean): Boolean {
        return speaker.speakAndAwait(text, flush)
    }

    override fun stop() {
        speaker.stop()
    }

    suspend fun awaitIdle() {
        speaker.awaitIdle()
    }

    val isSpeaking: Boolean
        get() = speaker.isSpeaking

    fun ensureReady() = speaker.ensureReady()
}
