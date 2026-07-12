package com.tetraploid.joyforold.speech.api

/**
 * 语音能力抽象层：ASR / TTS / 唤醒可替换实现（火山、讯飞、系统 TTS 等）。
 */
interface TtsOutput {
    fun speak(text: String, flush: Boolean = false)

    suspend fun speakAndAwait(text: String, flush: Boolean = false): Boolean

    fun stop()
}

data class SpeechInputSession(
    val shortUtterance: Boolean = false,
    val onPartialText: (String) -> Unit,
    val onFinalText: (String) -> Unit,
    val onError: (String) -> Unit,
)

interface SpeechInput {
    /** TTS 播报期间预连 ASR，缩短开麦后的首字丢失。 */
    suspend fun prepareConnection(session: SpeechInputSession) {}

    fun cancelPreparedConnection() {}

    /** 取消进行中的识别会话（不触发 onFinalText）。 */
    fun cancelActiveSession() {}

    fun start(session: SpeechInputSession)

    suspend fun stop(onFinalText: (String) -> Unit)

    fun isActive(): Boolean
}

/** 本地唤醒词引擎占位接口（当前由 WakeWordService + Sherpa 实现）。 */
interface WakeWordEngine {
    val phrase: String
    fun start()
    fun stop()
}

enum class VoiceInteractionState {
    Idle,
    SpeakingPrompt,
    Listening,
    Processing,
}
