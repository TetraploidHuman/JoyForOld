package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextConsentPhraseMatcherTest {
    @Test
    fun classify_grantAndDeny() {
        assertEquals(ContextConsentPhraseMatcher.Intent.GRANT, ContextConsentPhraseMatcher.classify("同意"))
        assertEquals(ContextConsentPhraseMatcher.Intent.GRANT, ContextConsentPhraseMatcher.classify("好的可以"))
        assertEquals(ContextConsentPhraseMatcher.Intent.DENY, ContextConsentPhraseMatcher.classify("取消"))
        assertEquals(ContextConsentPhraseMatcher.Intent.DENY, ContextConsentPhraseMatcher.classify("不要发送"))
        assertEquals(ContextConsentPhraseMatcher.Intent.DENY, ContextConsentPhraseMatcher.classify("不可以"))
        assertEquals(ContextConsentPhraseMatcher.Intent.DENY, ContextConsentPhraseMatcher.classify("不行"))
        assertEquals(ContextConsentPhraseMatcher.Intent.UNCLEAR, ContextConsentPhraseMatcher.classify("不太好"))
        assertEquals(ContextConsentPhraseMatcher.Intent.UNCLEAR, ContextConsentPhraseMatcher.classify("嗯"))
    }
}
