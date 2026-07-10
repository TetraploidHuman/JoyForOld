package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordAudioQualityTest {
    @Test
    fun isUsablePositiveSample_rejectsTooShort() {
        assertFalse(WakeWordAudioQuality.isUsablePositiveSample(ByteArray(100)))
    }

    @Test
    fun isUsablePositiveSample_acceptsLoudEnoughClip() {
        val pcm = ByteArray(16000 * 2)
        var i = 0
        while (i + 1 < pcm.size) {
            pcm[i] = 0x00
            pcm[i + 1] = 0x30
            i += 2
        }
        assertTrue(WakeWordAudioQuality.isUsablePositiveSample(pcm))
    }
}
