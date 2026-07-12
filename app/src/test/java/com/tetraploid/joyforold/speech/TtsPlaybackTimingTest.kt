package com.tetraploid.joyforold.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTimingTest {
    @Test
    fun shortPromptUsesSmallTail() {
        val ms = TtsPlaybackTiming.playbackTailMs("请说出您的指令")
        assertTrue(ms <= 600L)
        assertTrue(ms >= 80L)
    }

    @Test
    fun longResultUsesLargerTail() {
        val ms = TtsPlaybackTiming.playbackTailMs("这是一段比较长的助手回复，需要更长的播放尾音等待。")
        assertTrue(ms >= 200L)
    }

    @Test
    fun blankIsZero() {
        assertEquals(0L, TtsPlaybackTiming.playbackTailMs("   "))
    }
}
