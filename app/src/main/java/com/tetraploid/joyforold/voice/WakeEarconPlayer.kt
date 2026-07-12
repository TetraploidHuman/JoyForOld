package com.tetraploid.joyforold.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * 唤醒反馈短音（EarCon），替代 TTS「在呢，请说」以降低延迟与误识别。
 */
object WakeEarconPlayer {
    private const val TAG = "WakeEarconPlayer"
    private const val TONE_DURATION_MS = 120
    private const val RELEASE_DELAY_MS = TONE_DURATION_MS + 80L

    fun play(context: Context) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 72)
            generator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                generator.release()
            }, RELEASE_DELAY_MS)
        }.onFailure { error ->
            Log.w(TAG, "earcon play failed", error)
        }
    }
}
