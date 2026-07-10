package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaKeywordEncoderTest {
    @Test
    fun encodeHeyCortana_usesCmuPhones() {
        val lexicon = EnglishPhoneLexicon(null)
        val lines = SherpaKeywordEncoder.encodeKeywordVariants(
            keyword = "Hey,Cortana",
            lexicon = lexicon,
            keywordScore = 3.0f,
            keywordThreshold = 0.015f,
        )
        assertTrue(lines.isNotEmpty())
        val tokens = KeywordTokenValidator.modelingTokens(lines.first())
        assertEquals(listOf("HH", "EY1", "K", "AO1", "R", "T", "AE1", "N", "AH0"), tokens)
        assertTrue(lines.first().contains("@Hey,Cortana"))
    }

    @Test
    fun encodeLaotoule_stillUsesPinyinEncoder() {
        val lexicon = EnglishPhoneLexicon(null)
        val lines = SherpaKeywordEncoder.encodeKeywordVariants(
            keyword = "老头乐",
            lexicon = lexicon,
        )
        assertTrue(lines.first().contains("ǎo"))
        assertTrue(lines.first().contains("@老头乐"))
    }
}
