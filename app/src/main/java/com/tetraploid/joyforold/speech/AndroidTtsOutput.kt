package com.tetraploid.joyforold.speech

import android.content.Context
import com.tetraploid.joyforold.speech.api.TtsOutput
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    fun ensureReady() = speaker.ensureReady()
}
