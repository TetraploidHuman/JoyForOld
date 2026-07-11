package com.tetraploid.joyforold.speech

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.tetraploid.joyforold.speech.api.SpeechInput
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import com.tetraploid.joyforold.speech.api.TtsOutput
import com.tetraploid.joyforold.speech.api.VoiceInteractionState

/**
 * 语音轮次：先播再问，再听用户说话（参考 SightSync VoiceTurnCoordinator）。
 */
class VoiceTurnCoordinator(
    private val ttsOutput: TtsOutput,
    private val speechInput: SpeechInput,
    private val onStateChanged: (VoiceInteractionState) -> Unit,
) {
    companion object {
        private const val POST_TTS_LISTEN_DELAY_MS = 450L
    }
    suspend fun speakPromptThenListen(
        prompt: String?,
        session: SpeechInputSession,
    ) = coroutineScope {
        val prepareJob = async {
            runCatching { speechInput.prepareConnection(session) }
        }
        if (!prompt.isNullOrBlank()) {
            onStateChanged(VoiceInteractionState.SpeakingPrompt)
            ttsOutput.speakAndAwait(prompt, flush = true)
            delay(POST_TTS_LISTEN_DELAY_MS)
        }
        prepareJob.await()
        onStateChanged(VoiceInteractionState.Listening)
        speechInput.start(session)
    }

    suspend fun speakResult(text: String) {
        if (text.isBlank()) return
        onStateChanged(VoiceInteractionState.SpeakingPrompt)
        ttsOutput.speakAndAwait(text, flush = true)
        onStateChanged(VoiceInteractionState.Idle)
    }

    fun markProcessing() {
        onStateChanged(VoiceInteractionState.Processing)
    }

    fun markIdle() {
        onStateChanged(VoiceInteractionState.Idle)
    }

    fun cancelVoice() {
        ttsOutput.stop()
        speechInput.cancelPreparedConnection()
        onStateChanged(VoiceInteractionState.Idle)
    }
}
