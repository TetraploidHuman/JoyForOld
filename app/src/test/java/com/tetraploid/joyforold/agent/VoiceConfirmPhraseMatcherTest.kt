package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConfirmPhraseMatcherTest {
    @Test
    fun classify_confirm() {
        assertEquals(VoiceConfirmPhraseMatcher.Intent.CONFIRM, VoiceConfirmPhraseMatcher.classify("好的发送"))
    }

    @Test
    fun classify_cancel() {
        assertEquals(VoiceConfirmPhraseMatcher.Intent.CANCEL, VoiceConfirmPhraseMatcher.classify("算了不要了"))
    }

    @Test
    fun classify_unclear() {
        assertEquals(VoiceConfirmPhraseMatcher.Intent.UNCLEAR, VoiceConfirmPhraseMatcher.classify("QQ电话"))
    }
}
