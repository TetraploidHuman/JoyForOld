package com.tetraploid.joyforold.speech

import com.tetraploid.joyforold.speech.api.SpeechInput
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import com.tetraploid.joyforold.speech.api.TtsOutput
import com.tetraploid.joyforold.speech.api.VoiceInteractionState

/**
 * 语音轮次：TTS 完全播完后再连接 ASR、开麦，避免把播报内容识别进去。
 */
class VoiceTurnCoordinator(
    private val ttsOutput: TtsOutput,
    private val speechInput: SpeechInput,
    private val onStateChanged: (VoiceInteractionState) -> Unit,
    private val awaitTtsIdle: suspend () -> Unit = {},
) {
    suspend fun speakPromptThenListen(
        prompt: String?,
        session: SpeechInputSession,
    ) {
        if (!prompt.isNullOrBlank()) {
            onStateChanged(VoiceInteractionState.SpeakingPrompt)
            ttsOutput.speakAndAwait(prompt, flush = true)
        }
        awaitTtsIdle()
        runCatching { speechInput.prepareConnection(session) }
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
