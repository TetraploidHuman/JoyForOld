package com.tetraploid.joyforold.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

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

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
