package com.tetraploid.joyforold.speech

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class JoyTtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearSpeakingRunnable: Runnable? = null

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var speaking = false

    @Volatile
    private var pendingSpeakContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    val isSpeaking: Boolean
        get() = speaking

    @Synchronized
    fun ensureReady() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val zh = engine.setLanguage(Locale.CHINA)
                if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
                ready = true
            }
        }
    }

    fun speak(text: String, flush: Boolean = false) {
        val message = text.trim()
        if (message.isBlank()) return
        ensureReady()
        val engine = tts ?: return
        if (!ready) return

        markSpeaking(message)
        val expectedId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(createDoneListener(expectedId, message))
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(message, mode, null, expectedId)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(message, mode, null)
            scheduleSpeakingClear(message)
        }
    }

    suspend fun speakAndAwait(text: String, flush: Boolean = false): Boolean {
        val message = text.trim()
        if (message.isBlank()) return true
        ensureReady()
        if (!awaitReady()) return false
        val engine = tts ?: return false

        markSpeaking(message)
        val ok = suspendCancellableCoroutine { continuation ->
            pendingSpeakContinuation = continuation
            val expectedId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == expectedId && continuation.isActive) {
                            pendingSpeakContinuation = null
                            continuation.resume(true)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == expectedId && continuation.isActive) {
                            pendingSpeakContinuation = null
                            continuation.resume(false)
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == expectedId && continuation.isActive) {
                            pendingSpeakContinuation = null
                            continuation.resume(false)
                        }
                    }
                },
            )

            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                engine.speak(message, mode, null, expectedId)
            } else {
                @Suppress("DEPRECATION")
                engine.speak(message, mode, null)
            }
            if (queued == TextToSpeech.ERROR && continuation.isActive) {
                pendingSpeakContinuation = null
                continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                pendingSpeakContinuation = null
                engine.stop()
                clearSpeaking()
            }
        }
        if (ok) {
            delay(TtsPlaybackTiming.playbackTailMs(message))
        }
        clearSpeaking()
        return ok
    }

    suspend fun awaitIdle() {
        while (speaking) {
            delay(50)
        }
    }

    private suspend fun awaitReady(timeoutMs: Long = 3_000L): Boolean {
        if (ready) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ready && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return ready
    }

    fun stop() {
        tts?.stop()
        pendingSpeakContinuation?.let { continuation ->
            if (continuation.isActive) {
                pendingSpeakContinuation = null
                continuation.resume(false)
            }
        }
        clearSpeaking()
    }

    @Synchronized
    fun shutdown() {
        clearSpeaking()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun createDoneListener(expectedId: String, message: String): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == expectedId) {
                    scheduleSpeakingClear(message)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == expectedId) {
                    clearSpeaking()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == expectedId) {
                    clearSpeaking()
                }
            }
        }
    }

    private fun markSpeaking(message: String) {
        clearSpeakingRunnable?.let { mainHandler.removeCallbacks(it) }
        clearSpeakingRunnable = null
        speaking = true
    }

    private fun scheduleSpeakingClear(message: String) {
        clearSpeakingRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { clearSpeaking() }
        clearSpeakingRunnable = runnable
        mainHandler.postDelayed(runnable, TtsPlaybackTiming.playbackTailMs(message))
    }

    private fun clearSpeaking() {
        clearSpeakingRunnable?.let { mainHandler.removeCallbacks(it) }
        clearSpeakingRunnable = null
        speaking = false
    }
}
