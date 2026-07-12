package com.tetraploid.joyforold.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBargeInTriggerTest {
    @Test
    fun `requires grace period before triggering`() {
        val trigger = VoiceBargeInTrigger(gracePeriodMs = 400L, requiredConsecutiveHits = 1)
        trigger.reset(startedAtMs = 1_000L)
        assertFalse(trigger.onFrame(hasSpeech = true, nowMs = 1_200L))
        assertTrue(trigger.onFrame(hasSpeech = true, nowMs = 1_500L))
    }

    @Test
    fun `requires consecutive speech frames`() {
        val trigger = VoiceBargeInTrigger(gracePeriodMs = 0L, requiredConsecutiveHits = 3)
        trigger.reset(startedAtMs = 0L)
        assertFalse(trigger.onFrame(hasSpeech = true, nowMs = 100L))
        assertFalse(trigger.onFrame(hasSpeech = true, nowMs = 200L))
        assertTrue(trigger.onFrame(hasSpeech = true, nowMs = 300L))
        assertTrue(trigger.onFrame(hasSpeech = false, nowMs = 400L))
    }

    @Test
    fun `resets consecutive count on silence`() {
        val trigger = VoiceBargeInTrigger(gracePeriodMs = 0L, requiredConsecutiveHits = 2)
        trigger.reset(startedAtMs = 0L)
        assertFalse(trigger.onFrame(hasSpeech = true, nowMs = 100L))
        assertFalse(trigger.onFrame(hasSpeech = false, nowMs = 200L))
        assertFalse(trigger.onFrame(hasSpeech = true, nowMs = 300L))
        assertTrue(trigger.onFrame(hasSpeech = true, nowMs = 400L))
    }
}
