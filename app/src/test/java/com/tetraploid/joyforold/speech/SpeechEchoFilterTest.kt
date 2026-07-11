package com.tetraploid.joyforold.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEchoFilterTest {
    @Test
    fun stripEcho_removesExactPrompt() {
        val result = SpeechEchoFilter.stripEcho("在呢，请说", listOf("在呢，请说"))
        assertEquals("", result)
    }

    @Test
    fun stripEcho_removesPromptPrefix() {
        val result = SpeechEchoFilter.stripEcho("在呢，请说打开微信", listOf("在呢，请说"))
        assertEquals("打开微信", result)
    }

    @Test
    fun stripEcho_keepsUnrelatedText() {
        val result = SpeechEchoFilter.stripEcho("打开微信", listOf("在呢，请说"))
        assertEquals("打开微信", result)
    }
}
