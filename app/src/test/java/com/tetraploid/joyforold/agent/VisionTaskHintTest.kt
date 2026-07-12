package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTaskHintTest {
    @Test
    fun detects_text_entry_intent_without_app_name() {
        assertTrue(VisionTaskHint.commandLikelyNeedsTextEntry("在浏览器里搜索今天天气"))
        assertTrue(VisionTaskHint.commandLikelyNeedsTextEntry("给大女儿发消息，说晚上回家"))
    }

    @Test
    fun nudges_type_after_repeated_taps() {
        val cmd = "打开微信，给大女儿发消息，发一条测试消息"
        val steps = (1..3).map { i ->
            AgentStepRecord(
                step = i,
                action = AgentAction(action = "tap", targetText = "500,200"),
                result = ActionExecutionResult(success = true, summary = "ok"),
                pageDiff = "",
            )
        }
        val hint = VisionTaskHint.pageContextSupplement(cmd, steps, visionMode = true)
        assertTrue(hint.contains("type"))
    }

    @Test
    fun no_hint_when_type_already_succeeded() {
        val cmd = "发一条测试消息"
        val steps = listOf(
            AgentStepRecord(
                step = 1,
                action = AgentAction(action = "type", inputText = "测试"),
                result = ActionExecutionResult(success = true, summary = "ok"),
                pageDiff = "",
            ),
        )
        assertEquals("", VisionTaskHint.pageContextSupplement(cmd, steps, visionMode = true))
    }
}
