package com.tetraploid.joyforold.speech

import com.tetraploid.joyforold.speech.api.SpeechInput
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import com.tetraploid.joyforold.speech.api.TtsOutput
import com.tetraploid.joyforold.speech.api.VoiceInteractionState
import kotlinx.coroutines.delay

/**
 * 语音轮次：默认 TTS 播完再开麦；若启用打断，则在播报期间本地 VAD 检测人声并提前开麦。
 */
class VoiceTurnCoordinator(
    private val ttsOutput: TtsOutput,
    private val speechInput: SpeechInput,
    private val onStateChanged: (VoiceInteractionState) -> Unit,
    private val awaitTtsIdle: suspend () -> Unit = {},
    private val speakPromptBlocking: suspend (String) -> BargeInSpeakOutcome = { text ->
        ttsOutput.speakAndAwait(text, flush = true)
        BargeInSpeakOutcome.Completed
    },
    private val onBargeInPreRoll: (ByteArray) -> Unit = {},
) {
    suspend fun speakPromptThenListen(
        prompt: String?,
        session: SpeechInputSession,
    ) {
        if (!prompt.isNullOrBlank()) {
            onStateChanged(VoiceInteractionState.SpeakingPrompt)
            when (val outcome = speakPromptBlocking(prompt)) {
                is BargeInSpeakOutcome.BargedIn -> {
                    onBargeInPreRoll(outcome.preRollPcm)
                    delay(VoiceBargeInMonitor.ECHO_DECAY_MS)
                }
                BargeInSpeakOutcome.Completed -> awaitTtsIdle()
            }
        }
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
        speechInput.cancelActiveSession()
        speechInput.cancelPreparedConnection()
        onStateChanged(VoiceInteractionState.Idle)
    }
}
