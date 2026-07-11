package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaOnnxWakeWordDetectorTest {
    @Test
    fun defaultSecondStageThreshold_matchesStage1() {
        val stage1 = 0.012f
        val stage2 = SherpaOnnxWakeWordDetector.defaultSecondStageThreshold(stage1)
        assertEquals(stage1, stage2, 0.0001f)
    }
}
