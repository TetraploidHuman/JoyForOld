package com.tetraploid.joyforold.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStepAdvisorTest {
    @Test
    fun failedFindOnPage_suggestsGenericReplanWithoutPrescribingSearch() {
        val session = AgentConversationSession(rootCommand = "哔哩哔哩打开一个视频，视频的名字叫我要吃饭")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "find_on_page", targetText = "我要吃饭"),
            result = ActionExecutionResult(false, "未找到"),
            pageDiff = "",
        )

        val hint = AgentStepAdvisor.postStepHint(
            session = session,
            action = AgentAction(action = "find_on_page", targetText = "我要吃饭"),
            result = ActionExecutionResult(false, "未找到"),
            snapshot = null,
            rootCommand = session.rootCommand,
        )

        assertNotNull(hint)
        assertTrue(hint!!.contains("read_tree"))
        assertTrue(!hint.contains("搜索框"))
    }

    @Test
    fun successfulTapWithNoPageChange_suggestsOtherCoordsNotReadTree() {
        val hint = AgentStepAdvisor.postStepHint(
            session = AgentConversationSession(rootCommand = "任意指令"),
            action = AgentAction(action = "tap", targetText = "500,100"),
            result = ActionExecutionResult(true, "已点击"),
            snapshot = null,
            rootCommand = "任意指令",
            pageDiff = VisionScreenChange.UNCHANGED_MARKER,
            visionMode = true,
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("tap"))
        assertTrue(!hint.contains("read_tree"))
    }
}
