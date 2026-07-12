package com.tetraploid.joyforold.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopStateTest {
    @Test
    fun tracksNoChangeAndSameActionCounts() {
        val state = AgentLoopState()
        state.afterStep(
            AgentAction(action = "click", targetText = "搜索"),
            pageDiff = "页面指纹未变",
        )
        state.afterStep(
            AgentAction(action = "click", targetText = "搜索"),
            pageDiff = "页面指纹未变",
        )
        state.afterStep(
            AgentAction(action = "click", targetText = "搜索"),
            pageDiff = "页面指纹未变",
        )
        val warnings = AgentLoopState.formatWarnings(state, stepNo = 4, maxSteps = 30)
        assertTrue(warnings.contains("连续 3 步无明显变化"))
        assertTrue(warnings.contains("相同操作已连续"))
    }

    @Test
    fun resetsNoChangeCountWhenPageChanges() {
        val state = AgentLoopState()
        state.afterStep(AgentAction(action = "click", targetText = "A"), pageDiff = "页面指纹未变")
        state.afterStep(AgentAction(action = "click", targetText = "B"), pageDiff = "新增可见文字(1): 小雨中")
        val warnings = AgentLoopState.formatWarnings(state, stepNo = 2, maxSteps = 30)
        assertTrue(!warnings.contains("连续 2 步"))
    }
}
