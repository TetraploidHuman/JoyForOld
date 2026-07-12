package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDoNotRepeatTest {
    @Test
    fun includesFailedSteps() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "搜索"),
            result = ActionExecutionResult(false, "未找到"),
            pageDiff = "",
        )
        val entries = AgentDoNotRepeat.buildFrom(session)
        assertTrue(entries.any { it.description.contains("搜索") })
        assertTrue(entries.first().reason.contains("未找到"))
    }

    @Test
    fun includesSuccessfulStepsWithNoPageChange() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "搜索"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "页面指纹未变（可能仍在同一屏或变化较小）",
        )
        val prompt = AgentDoNotRepeat.formatForPrompt(AgentDoNotRepeat.buildFrom(session))
        assertTrue(prompt.contains("禁止重复"))
        assertTrue(prompt.contains("搜索"))
    }

    @Test
    fun skipsSuccessfulStepsWithPageChange() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "视频"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "新增可见文字(1): 小雨中",
        )
        assertTrue(AgentDoNotRepeat.buildFrom(session).isEmpty())
    }
}
