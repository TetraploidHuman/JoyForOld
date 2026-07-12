package com.tetraploid.joyforold.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.tetraploid.joyforold.wakeword.SpeechActivityGate
import com.tetraploid.joyforold.wakeword.WakeWordAudioRingBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * TTS 播报期间在本地开麦做 VAD，检测到人声即触发打断；音频不送云端，仅保留 ring 供 ASR 预卷。
 */
class VoiceBargeInMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val ringBuffer = WakeWordAudioRingBuffer()
    private val gate = SpeechActivityGate(rmsThreshold = BARGE_IN_RMS_THRESHOLD, hangoverMs = 120L)
    private val trigger = VoiceBargeInTrigger()
    private val bargeInSignal = Channel<Unit>(Channel.CONFLATED)

    private var record: AudioRecord? = null

    suspend fun awaitBargeIn() {
        bargeInSignal.receive()
    }

    fun takePreRoll(): ByteArray = ringBuffer.snapshot()

    suspend fun runUntilStopped() {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(min, SAMPLE_RATE * 2)
        val audio = createAudioRecord(bufferSize) ?: return
        record = audio
        trigger.reset()
        runCatching { audio.startRecording() }
            .onFailure {
                Log.w(TAG, "barge-in monitor start failed", it)
                releaseRecorder()
                return
            }

        val buf = ByteArray(FRAME_BYTES)
        try {
            while (coroutineContext.isActive) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) {
                    delay(10)
                    continue
                }
                ringBuffer.append(buf, n)
                val now = System.currentTimeMillis()
                if (trigger.onFrame(gate.hasSpeech(buf, n), now)) {
                    bargeInSignal.trySend(Unit)
                    break
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } finally {
            releaseRecorder()
        }
    }

    fun release() {
        releaseRecorder()
        ringBuffer.clear()
        while (bargeInSignal.tryReceive().isSuccess) Unit
    }

    private fun releaseRecorder() {
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val preferred = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (preferred.state == AudioRecord.STATE_INITIALIZED) return preferred
        preferred.release()
        val fallback = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (fallback.state == AudioRecord.STATE_INITIALIZED) return fallback
        fallback.release()
        return null
    }

    companion object {
        private const val TAG = "VoiceBargeInMonitor"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200
        const val BARGE_IN_RMS_THRESHOLD = 850.0
        /** 停播后稍等扬声器余音衰减再开 ASR。 */
        const val ECHO_DECAY_MS = 80L
    }
}
