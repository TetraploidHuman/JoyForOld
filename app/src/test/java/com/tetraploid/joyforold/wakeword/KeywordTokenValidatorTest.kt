package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordTokenValidatorTest {
    @Test
    fun modelingTokens_stopsBeforeExtras() {
        val line = "HH EY1 K AO1 R T AE1 N AH0 :3.0 #0.015 @Hey,Cortana"
        assertEquals(
            listOf("HH", "EY1", "K", "AO1", "R", "T", "AE1", "N", "AH0"),
            KeywordTokenValidator.modelingTokens(line),
        )
        assertEquals("Hey,Cortana", KeywordTokenValidator.label(line))
    }

    @Test
    fun validateLine_checksModelTokens() {
        val tokens = setOf("HH", "EY1", "K", "AO1", "R", "T", "AE1", "N", "AH0")
        val line = "HH EY1 K AO1 R T AE1 N AH0 @Hey,Cortana"
        assertTrue(KeywordTokenValidator.validateLine(line, tokens))
        assertFalse(KeywordTokenValidator.validateLine("HH XX1 @bad", tokens))
    }
}
