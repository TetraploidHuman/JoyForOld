package com.tetraploid.joyforold.wakeword

/**
 * Second-stage wake verification: re-run KWS on the recent audio ring buffer
 * with a stricter threshold before accepting a candidate hit.
 */
class WakeWordSecondStageVerifier(
    private val detector: SherpaOnnxWakeWordDetector,
) {
    fun verify(ringBuffer: WakeWordAudioRingBuffer): Boolean {
        val pcm = ringBuffer.snapshot()
        if (pcm.size < MIN_BYTES) return false
        return detector.verifyBuffered(pcm, pcm.size)
    }

    companion object {
        private const val MIN_BYTES = 16000 / 10 * 2
    }
}
