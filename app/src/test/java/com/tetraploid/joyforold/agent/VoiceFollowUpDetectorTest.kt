package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFollowUpDetectorTest {
    @Test
    fun openAppCommand_recognizedAsNewCommand() {
        assertTrue(VoiceFollowUpDetector.looksLikeNewCommand("帮我打开bilibili"))
    }

    @Test
    fun shortReply_notNewCommand() {
        assertFalse(VoiceFollowUpDetector.looksLikeNewCommand("Yuki"))
    }
}
