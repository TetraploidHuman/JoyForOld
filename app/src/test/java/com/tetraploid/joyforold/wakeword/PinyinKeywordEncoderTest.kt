package com.tetraploid.joyforold.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinKeywordEncoderTest {
    @Test
    fun encodeLaotoule_usesCaronToneMarks() {
        val lines = PinyinKeywordEncoder.encodeKeywordVariants("老头乐")
        assertTrue(lines.isNotEmpty())
        val line = lines.first()
        assertTrue(line.contains("ǎo"))
        assertTrue(line.contains("óu"))
        assertTrue(line.contains("è"))
        assertFalse(line.contains("ăo"))
        assertTrue(line.contains("@老头乐"))
    }

    @Test
    fun encodeLaotoule_includesRelaxedVariant() {
        val lines = PinyinKeywordEncoder.encodeKeywordVariants("老头乐")
        assertTrue(lines.size >= 2)
        assertTrue(lines.any { it.contains(" t ou ") && it.contains(" l e ") && it.contains("@老头乐") })
        assertFalse(lines.any { it.contains(" l ao ") })
    }
}
