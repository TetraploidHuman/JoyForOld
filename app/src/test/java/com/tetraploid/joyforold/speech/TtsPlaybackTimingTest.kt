package com.tetraploid.joyforold.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTimingTest {
    @Test
    fun playbackTailMs_scalesWithTextLength() {
        assertEquals(0L, TtsPlaybackTiming.playbackTailMs(""))
        assertTrue(TtsPlaybackTiming.playbackTailMs("在呢，请说") >= 300L)
        assertTrue(TtsPlaybackTiming.playbackTailMs("a".repeat(100)) <= 8_000L)
    }
}
