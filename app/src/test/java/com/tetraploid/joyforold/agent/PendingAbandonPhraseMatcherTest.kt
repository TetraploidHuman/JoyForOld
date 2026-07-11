package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingAbandonPhraseMatcherTest {
    @Test
    fun classify_abandon() {
        assertEquals(PendingAbandonPhraseMatcher.Intent.ABANDON, PendingAbandonPhraseMatcher.classify("放弃吧"))
    }

    @Test
    fun classify_continue() {
        assertEquals(PendingAbandonPhraseMatcher.Intent.CONTINUE, PendingAbandonPhraseMatcher.classify("继续原来的"))
    }
}
