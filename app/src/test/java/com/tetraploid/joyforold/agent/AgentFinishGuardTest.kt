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

    @Test
    fun blocksPrematureFinishOnMapPoiList() {
        val session = AgentConversationSession(rootCommand = "带我去最近的肯德基")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "open_app", targetText = "高德地图"),
            result = ActionExecutionResult(true, "已打开"),
            pageDiff = "",
        )
        session.recordStep(
            step = 2,
            action = AgentAction(action = "type", inputText = "肯德基"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "com.autonavi.minimap",
            appHint = "当前为高德地图",
            clickables = listOf("肯德基(桂阳向阳路店)", "路线", "打车"),
            editables = listOf("EditText text=\"肯德基\""),
            visibleTexts = listOf("肯德基", "路线", "打车"),
            sendButtons = emptyList(),
            fingerprint = "kfc-list",
        )
        val reason = AgentFinishGuard.prematureFinishReason(
            session = session,
            action = AgentAction(action = "finish", message = "已为您找到附近的肯德基", finished = true),
            snapshot = snapshot,
            rootCommand = session.rootCommand,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("导航"))
    }

    @Test
    fun allowsFinishAfterNavigateToDeepLink() {
        val session = AgentConversationSession(rootCommand = "带我去桂阳一中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "navigate_to", targetText = "桂阳一中"),
            result = ActionExecutionResult(true, "已尝试打开高德地图并导航前往：桂阳一中"),
            pageDiff = "",
        )
        assertNull(
            AgentFinishGuard.prematureFinishReason(
                session = session,
                action = AgentAction(action = "finish", message = "正在为您导航前往：桂阳一中", finished = true),
                snapshot = StructuredPageSnapshot(
                    packageName = "com.autonavi.minimap",
                    appHint = "当前为高德地图",
                    clickables = listOf("路线", "打车"),
                    editables = emptyList(),
                    visibleTexts = listOf("桂阳一中", "路线"),
                    sendButtons = emptyList(),
                    fingerprint = "school",
                ),
                rootCommand = session.rootCommand,
            ),
        )
    }

    @Test
    fun allowsFinishWhenNavigationStarted() {
        val session = AgentConversationSession(rootCommand = "带我去附近的公园")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "开始导航"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "",
        )
        assertNull(
            AgentFinishGuard.prematureFinishReason(
                session = session,
                action = AgentAction(action = "finish", message = "已为您规划导航前往：公园", finished = true),
                snapshot = StructuredPageSnapshot(
                    packageName = "com.autonavi.minimap",
                    appHint = "当前为高德地图",
                    clickables = listOf("退出导航", "继续导航"),
                    editables = emptyList(),
                    visibleTexts = listOf("正在导航", "公园"),
                    sendButtons = emptyList(),
                    fingerprint = "nav-started",
                ),
                rootCommand = session.rootCommand,
            ),
        )
    }
}
