package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackHeuristicsTest {
    @Test
    fun detectsVideoDetailPageWithPartialTypedQuery() {
        val session = AgentConversationSession(rootCommand = "打开哔哩哔哩，然后播放小雨中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "赵雷的小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("【4Khires】赵雷《小雨中》现场版"),
            editables = emptyList(),
            visibleTexts = listOf("15.7万播放", "171条弹幕", "1人正在看"),
            sendButtons = emptyList(),
            fingerprint = "video-detail",
        )
        assertTrue(
            MediaPlaybackHeuristics.isOnVideoDetailPage(snapshot, session, session.rootCommand),
        )
    }

    @Test
    fun detectsVideoDetailPageWithQuery() {
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("【4Khires】赵雷《小雨中》现场版"),
            editables = emptyList(),
            visibleTexts = listOf("15.7万播放", "171条弹幕", "1人正在看"),
            sendButtons = emptyList(),
            fingerprint = "video-detail",
        )
        assertTrue(MediaPlaybackHeuristics.isOnVideoDetailPage(snapshot, "小雨中"))
    }

    @Test
    fun rejectsSearchResultsPage() {
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("赵雷《小雨中》现场版"),
            editables = emptyList(),
            visibleTexts = listOf("综合", "番剧", "15.7万"),
            sendButtons = emptyList(),
            fingerprint = "search",
        )
        assertFalse(MediaPlaybackHeuristics.isOnVideoDetailPage(snapshot, "小雨中"))
    }

    @Test
    fun interceptsAbstractPlaybackClickOnDetailPage() {
        val session = AgentConversationSession(rootCommand = "打开哔哩哔哩，然后播放小雨中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "赵雷的小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        session.recordStep(
            step = 2,
            action = AgentAction(action = "click", targetText = "【4Khires】赵雷《小雨中》现场版"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("【4Khires】赵雷《小雨中》现场版"),
            editables = emptyList(),
            visibleTexts = listOf("15.7万播放", "171条弹幕"),
            sendButtons = emptyList(),
            fingerprint = "video-detail",
        )
        val intercepted = MediaPlaybackHeuristics.interceptStuckPlaybackAction(
            session = session,
            snapshot = snapshot,
            rootCommand = session.rootCommand,
            action = AgentAction(action = "click", targetText = "视频播放区域"),
        )
        assertNotNull(intercepted)
        assertTrue(intercepted!!.action.equals("finish", ignoreCase = true))
        assertTrue(intercepted.message.orEmpty().contains("小雨中"))
    }

    @Test
    fun doesNotInterceptBeforeVideoClick() {
        val session = AgentConversationSession(rootCommand = "播放小雨中")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "小雨中"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val snapshot = StructuredPageSnapshot(
            packageName = "tv.danmaku.bili",
            appHint = "",
            clickables = listOf("小雨中"),
            editables = emptyList(),
            visibleTexts = listOf("15.7万播放", "171条弹幕"),
            sendButtons = emptyList(),
            fingerprint = "video-detail",
        )
        val intercepted = MediaPlaybackHeuristics.interceptStuckPlaybackAction(
            session = session,
            snapshot = snapshot,
            rootCommand = session.rootCommand,
            action = AgentAction(action = "click", targetText = "视频播放区域"),
        )
        assertNull(intercepted)
    }
}
