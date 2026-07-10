package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Records short PCM clips on-device for wake-word threshold calibration.
 */
class WakeWordCalibrationSession(
    context: Context,
    private val phrase: String,
    private val keywordScore: Float,
    private val keywordThreshold: Float,
) {
    private val appContext = context.applicationContext
    private val detector = SherpaOnnxWakeWordDetector(
        context = appContext,
        keyword = phrase,
        keywordScore = keywordScore,
        keywordThreshold = keywordThreshold,
    )
    private val calibrator = WakeWordCalibrator(detector)
    private var record: AudioRecord? = null

    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        detector.prepare()
    }

    fun positiveCount(): Int = calibrator.positiveCount()

    fun needsNegativeSample(): Boolean = positiveCount() >= POSITIVE_TARGET

    suspend fun recordPositiveSample(): Boolean {
        val pcm = recordSample(POSITIVE_MS) ?: return false
        calibrator.addPositiveSample(pcm)
        return true
    }

    suspend fun recordNegativeSample(): Boolean {
        val pcm = recordSample(NEGATIVE_MS) ?: return false
        calibrator.setNegativeSample(pcm)
        return true
    }

    fun calibrate(): WakeWordCalibrator.Result? =
        calibrator.calibrate(keywordScore, keywordThreshold)

    fun release() {
        releaseRecorder()
        detector.release()
    }

    private suspend fun recordSample(durationMs: Int): ByteArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(min, SAMPLE_RATE * 2)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return@withContext null
        }
        record = audio
        val totalBytes = SAMPLE_RATE * 2 * durationMs / 1000
        val out = ByteArray(totalBytes)
        var offset = 0
        val buf = ByteArray(3200)
        runCatching { audio.startRecording() }.onFailure {
            releaseRecorder()
            return@withContext null
        }
        try {
            while (offset < totalBytes) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val copy = minOf(n, totalBytes - offset)
                System.arraycopy(buf, 0, out, offset, copy)
                offset += copy
            }
        } finally {
            releaseRecorder()
        }
        out.copyOf(offset)
    }

    private fun releaseRecorder() {
        record?.runCatching {
            stop()
            release()
        }
        record = null
    }

    companion object {
        const val POSITIVE_MS = 1800
        const val NEGATIVE_MS = 5000
        const val POSITIVE_TARGET = 3
        private const val SAMPLE_RATE = 16000
    }
}
