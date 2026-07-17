package com.tetraploid.joyforold.agent.actionsets.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSetAskPolicyTest {

    @Test
    fun unresolvedFields_reportsBlankOnly() {
        val params = ActionSetParams(mapOf("query" to "手机", "product" to ""))
        assertEquals(listOf("product"), ActionSetAskPolicy.unresolvedFields(params, listOf("product", "query")))
        assertTrue(ActionSetAskPolicy.unresolvedFields(params, listOf("query")).isEmpty())
    }

    @Test
    fun shouldRetry_onlyOnce() {
        assertTrue(ActionSetAskPolicy.shouldRetry(0))
        assertFalse(ActionSetAskPolicy.shouldRetry(1))
        assertFalse(ActionSetAskPolicy.shouldRetry(2))
    }

    @Test
    fun abortFinishMessage_includesFieldHint() {
        val msg = ActionSetAskPolicy.abortFinishMessage(listOf("product"))
        assertTrue(msg.contains("product"))
        assertTrue(msg.contains("列表"))
    }
}
