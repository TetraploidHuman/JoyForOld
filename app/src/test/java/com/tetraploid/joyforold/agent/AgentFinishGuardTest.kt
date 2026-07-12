package com.tetraploid.joyforold.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFinishGuardTest {
    @Test
    fun blocksFinishWithNoInteractiveSteps() {
        val session = AgentConversationSession(rootCommand = "我要听小雨中")
        val reason = AgentFinishGuard.prematureFinishReason(
            session = session,
            action = AgentAction(action = "finish", message = "已开始播放小雨中", finished = true),
            snapshot = null,
            rootCommand = "我要听小雨中",
        )
        assertNotNull(reason)
    }

    @Test
    fun allowsFinishAfterInteractiveSteps() {
        val session = AgentConversationSession(rootCommand = "我要听小雨中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        session.recordStep(
            step = 2,
            action = AgentAction(action = "click", targetText = "小雨中"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "",
        )
        assertNull(
            AgentFinishGuard.prematureFinishReason(
                session = session,
                action = AgentAction(action = "finish", message = "小雨中正在播放", finished = true),
                snapshot = null,
                rootCommand = "我要听小雨中",
            ),
        )
    }

    @Test
    fun blocksFinishWhenTypedQueryMissingFromPage() {
        val session = AgentConversationSession(rootCommand = "去哔哩哔哩搜索小雨中然后播放")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("视频，GALA Young For You"),
            editables = emptyList(),
            visibleTexts = listOf("许巍", "理想"),
            sendButtons = emptyList(),
            fingerprint = "wrong-video",
        )
        val reason = AgentFinishGuard.prematureFinishReason(
            session = session,
            action = AgentAction(action = "finish", message = "已为您播放赵雷的小雨中", finished = true),
            snapshot = snapshot,
            rootCommand = session.rootCommand,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("小雨中"))
    }
}
