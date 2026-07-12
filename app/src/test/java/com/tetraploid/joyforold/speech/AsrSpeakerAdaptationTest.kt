package com.tetraploid.joyforold.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrSpeakerAdaptationTest {
    @Test
    fun adapt_stripsWakePhraseAndAppliesCorrections() {
        val adapted = AsrSpeakerAdaptation.adapt(
            recognized = "小乐小乐打开蓝牙",
            wakePhrase = "小乐",
            corrections = mapOf("打开蓝呀" to "打开蓝牙"),
        )
        assertEquals("打开蓝牙", adapted)
    }

    @Test
    fun adapt_appliesHomophoneMap() {
        val adapted = AsrSpeakerAdaptation.adapt(
            recognized = "打开蓝呀",
            wakePhrase = null,
            corrections = mapOf("打开蓝呀" to "打开蓝牙"),
        )
        assertEquals("打开蓝牙", adapted)
    }
}
