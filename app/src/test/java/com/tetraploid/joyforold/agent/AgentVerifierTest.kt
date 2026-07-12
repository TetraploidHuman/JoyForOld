package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVerifierTest {
    private fun snapshot(
        pkg: String = "com.example.app",
        fingerprint: String = "fp-$pkg",
        appHint: String = "",
    ) = StructuredPageSnapshot(
        packageName = pkg,
        appHint = appHint,
        clickables = listOf("搜索"),
        editables = emptyList(),
        visibleTexts = listOf("首页"),
        sendButtons = emptyList(),
        fingerprint = fingerprint,
    )

    @Test
    fun failedExecution_alwaysFailsVerification() {
        val result = AgentVerifier.verify(
            action = AgentAction(action = "click", targetText = "搜索"),
            executionResult = ActionExecutionResult(false, "未找到"),
            beforeSnapshot = snapshot(),
            afterSnapshot = snapshot(),
            pageDiff = "",
        )
        assertEquals(AgentVerificationStatus.FAILED, result.status)
    }

    @Test
    fun clickWithNoPageChange_failsVerification() {
        val snap = snapshot()
        val result = AgentVerifier.verify(
            action = AgentAction(action = "click", targetText = "搜索"),
            executionResult = ActionExecutionResult(true, "已点击"),
            beforeSnapshot = snap,
            afterSnapshot = snap,
            pageDiff = "页面指纹未变（可能仍在同一屏或变化较小）",
        )
        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertTrue(result.message.contains("指纹未变"))
    }

    @Test
    fun clickWithPageChange_passesVerification() {
        val before = StructuredPageSnapshot(
            packageName = "com.example.app",
            appHint = "",
            clickables = listOf("首页", "推荐"),
            editables = emptyList(),
            visibleTexts = listOf("首页"),
            sendButtons = emptyList(),
            fingerprint = "fp-before",
        )
        val after = StructuredPageSnapshot(
            packageName = "com.example.app",
            appHint = "",
            clickables = listOf("搜索", "小雨中", "播放"),
            editables = emptyList(),
            visibleTexts = listOf("首页", "小雨中"),
            sendButtons = emptyList(),
            fingerprint = "fp-after",
        )
        val result = AgentVerifier.verify(
            action = AgentAction(action = "click", targetText = "视频"),
            executionResult = ActionExecutionResult(true, "已点击"),
            beforeSnapshot = before,
            afterSnapshot = after,
            pageDiff = "新增可见文字(1): 小雨中",
        )
        assertEquals(AgentVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun openAppWithoutSnapshot_isNotApplicable_notFailed() {
        val result = AgentVerifier.verify(
            action = AgentAction(action = "open_app", targetText = "哔哩哔哩"),
            executionResult = ActionExecutionResult(true, "已打开"),
            beforeSnapshot = null,
            afterSnapshot = null,
            pageDiff = "",
        )
        assertEquals(AgentVerificationStatus.NOT_APPLICABLE, result.status)
    }

    @Test
    fun openAppWithoutChange_failsVerification() {
        val snap = snapshot(pkg = "com.danmaku.bili", appHint = "当前为哔哩哔哩")
        val result = AgentVerifier.verify(
            action = AgentAction(action = "open_app", targetText = "哔哩哔哩"),
            executionResult = ActionExecutionResult(true, "已打开"),
            beforeSnapshot = snap,
            afterSnapshot = snap,
            pageDiff = "页面指纹未变",
        )
        assertEquals(AgentVerificationStatus.FAILED, result.status)
    }

    @Test
    fun clickWithOnlyMinorStructuralChange_failsVerification() {
        val before = StructuredPageSnapshot(
            packageName = "com.danmaku.bili",
            appHint = "当前为哔哩哔哩",
            clickables = listOf("搜索", "赵雷的小雨中"),
            editables = listOf("search_src_text"),
            visibleTexts = listOf("搜索", "搜索历史"),
            sendButtons = emptyList(),
            fingerprint = "bili-before",
        )
        val after = StructuredPageSnapshot(
            packageName = "com.danmaku.bili",
            appHint = "当前为哔哩哔哩",
            clickables = listOf("搜索", "赵雷的小雨中"),
            editables = listOf("search_src_text"),
            visibleTexts = listOf("搜索", "搜索发现"),
            sendButtons = emptyList(),
            fingerprint = "bili-after",
        )
        val result = AgentVerifier.verify(
            action = AgentAction(action = "click", targetText = "搜索"),
            executionResult = ActionExecutionResult(true, "已点击"),
            beforeSnapshot = before,
            afterSnapshot = after,
            pageDiff = "新增可见文字(1): 搜索发现",
        )
        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertTrue(
            result.message.contains("未变") || result.message.contains("搜索输入界面"),
        )
    }

    @Test
    fun clickWhenClickablesSetUnchanged_failsVerification() {
        val before = StructuredPageSnapshot(
            packageName = "com.danmaku.bili",
            appHint = "当前为哔哩哔哩",
            clickables = listOf("搜索", "赵雷 小雨中"),
            editables = listOf("""EditText(中部) id="search_src_text" text="赵雷 小雨中""""),
            visibleTexts = listOf("搜索"),
            sendButtons = emptyList(),
            fingerprint = "bili-edit",
        )
        val after = StructuredPageSnapshot(
            packageName = "com.danmaku.bili",
            appHint = "当前为哔哩哔哩",
            clickables = listOf("搜索", "赵雷 小雨中"),
            editables = emptyList(),
            visibleTexts = listOf("搜索"),
            sendButtons = emptyList(),
            fingerprint = "bili-fake",
        )
        val result = AgentVerifier.verify(
            action = AgentAction(action = "click", targetText = "搜索"),
            executionResult = ActionExecutionResult(true, "已点击"),
            beforeSnapshot = before,
            afterSnapshot = after,
            pageDiff = "页面有更新，但可点击/文字列表变化不明显",
        )
        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertTrue(
            result.message.contains("可点击项集合未变") ||
                result.message.contains("可点击/输入区未变"),
        )
    }

    @Test
    fun readTree_isNotApplicable() {
        val result = AgentVerifier.verify(
            action = AgentAction(action = "read_tree"),
            executionResult = ActionExecutionResult(true, "已读取"),
            beforeSnapshot = snapshot(),
            afterSnapshot = snapshot(),
            pageDiff = "",
        )
        assertEquals(AgentVerificationStatus.NOT_APPLICABLE, result.status)
    }
}
