package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFinishGuardTest {
    @Test
    fun extractTargetPhrase_fromListenCommand() {
        assertEquals("小雨中", AgentFinishGuard.extractTargetPhrase("我要听小雨中"))
    }

    @Test
    fun impliesTargetSelection_forActionCommands() {
        assertTrue(AgentFinishGuard.impliesTargetSelection("我要听小雨中"))
        assertTrue(AgentFinishGuard.impliesTargetSelection("打开微信"))
    }

    @Test
    fun blocksFinishAfterTypeOnly() {
        val session = AgentConversationSession(rootCommand = "我要听小雨中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "com.app",
            appHint = "",
            clickables = listOf("小雨中", "播放"),
            editables = listOf("搜索框"),
            visibleTexts = listOf("小雨中", "搜索结果"),
            sendButtons = emptyList(),
            fingerprint = "fp",
        )
        val reason = AgentFinishGuard.prematureFinishReason(
            session = session,
            action = AgentAction(action = "finish", message = "已开始播放小雨中", finished = true),
            snapshot = snapshot,
            rootCommand = "我要听小雨中",
        )
        assertNotNull(reason)
    }

    @Test
    fun allowsFinishAfterClickAndVisibleTarget() {
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
        val snapshot = StructuredPageSnapshot(
            packageName = "com.app",
            appHint = "",
            clickables = listOf("暂停"),
            editables = emptyList(),
            visibleTexts = listOf("小雨中", "正在播放"),
            sendButtons = emptyList(),
            fingerprint = "fp2",
        )
        assertNull(
            AgentFinishGuard.prematureFinishReason(
                session = session,
                action = AgentAction(action = "finish", message = "小雨中正在播放", finished = true),
                snapshot = snapshot,
                rootCommand = "我要听小雨中",
            ),
        )
    }
}
