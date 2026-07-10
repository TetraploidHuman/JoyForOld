package com.tetraploid.joyforold.wakeword

/**
 * Ring buffer for the most recent PCM16 mono audio, used by the second-stage wake verifier.
 */
class WakeWordAudioRingBuffer(
    capacityBytes: Int = SAMPLE_RATE * BYTES_PER_SAMPLE * BUFFER_SECONDS,
) {
    private val buffer = ByteArray(capacityBytes.coerceAtLeast(SAMPLE_RATE))
    private var writePos = 0
    private var filled = 0

    fun append(data: ByteArray, len: Int) {
        if (len <= 0) return
        val count = minOf(len, data.size)
        for (i in 0 until count) {
            buffer[writePos] = data[i]
            writePos = (writePos + 1) % buffer.size
            filled = minOf(filled + 1, buffer.size)
        }
    }

    fun snapshot(): ByteArray {
        if (filled == 0) return ByteArray(0)
        val out = ByteArray(filled)
        val start = (writePos - filled + buffer.size) % buffer.size
        if (start + filled <= buffer.size) {
            System.arraycopy(buffer, start, out, 0, filled)
        } else {
            val firstPart = buffer.size - start
            System.arraycopy(buffer, start, out, 0, firstPart)
            System.arraycopy(buffer, 0, out, firstPart, filled - firstPart)
        }
        return out
    }

    fun clear() {
        writePos = 0
        filled = 0
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_SECONDS = 2
    }
}
