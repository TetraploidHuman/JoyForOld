package com.tetraploid.joyforold.offline.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IntentFeatureEncoderTest {
    @Test
    fun encode_producesNormalizedVector() {
        val vec = IntentFeatureEncoder.encode("打开蓝牙")
        var sum = 0f
        for (v in vec) sum += v * v
        assertEquals(true, sum in 0.99f..1.01f)
    }

    @Test
    fun encode_sameInput_sameOutput() {
        val a = IntentFeatureEncoder.encode("打开无线网")
        val b = IntentFeatureEncoder.encode("打开无线网")
        assertEquals(a.toList(), b.toList())
    }
}
