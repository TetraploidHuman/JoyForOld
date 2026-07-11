package com.tetraploid.joyforold.speech

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class JoyTtsSpeaker(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

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
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(message, mode, null, UUID.randomUUID().toString())
    }

    suspend fun speakAndAwait(text: String, flush: Boolean = false): Boolean {
        val message = text.trim()
        if (message.isBlank()) return true
        ensureReady()
        val engine = tts ?: return false
        if (!ready) return false

        return suspendCancellableCoroutine { continuation ->
            val expectedId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == expectedId && continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == expectedId && continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == expectedId && continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            })

            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                engine.speak(message, mode, null, expectedId)
            } else {
                @Suppress("DEPRECATION")
                engine.speak(message, mode, null)
            }
            if (queued == TextToSpeech.ERROR && continuation.isActive) {
                continuation.resume(false)
            }
            continuation.invokeOnCancellation { engine.stop() }
        }
    }

    fun stop() {
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
