package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxWakeWordDetectorTest {
    @Test
    fun defaultSecondStageThreshold_isStricterThanStage1() {
        val stage1 = 0.012f
        val stage2 = SherpaOnnxWakeWordDetector.defaultSecondStageThreshold(stage1)
        assertTrue(stage2 > stage1)
        assertEquals(0.0198f, stage2, 0.0001f)
    }
}
