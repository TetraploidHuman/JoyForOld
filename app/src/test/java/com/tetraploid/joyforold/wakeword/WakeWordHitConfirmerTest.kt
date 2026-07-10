package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordHitConfirmerTest {
    @Test
    fun singleHitMode_triggersImmediately() {
        val confirmer = WakeWordHitConfirmer(requiredHits = 1)
        assertTrue(confirmer.onCandidateHit(1000L))
    }

    @Test
    fun doubleHitMode_requiresTwoHitsInWindow() {
        val confirmer = WakeWordHitConfirmer(requiredHits = 2, windowMs = 900L)
        assertFalse(confirmer.onCandidateHit(1000L))
        assertTrue(confirmer.onCandidateHit(1200L))
    }

    @Test
    fun doubleHitMode_expiresOldHits() {
        val confirmer = WakeWordHitConfirmer(requiredHits = 2, windowMs = 500L)
        assertFalse(confirmer.onCandidateHit(1000L))
        assertFalse(confirmer.onCandidateHit(2000L))
        assertTrue(confirmer.onCandidateHit(2400L))
    }
}
