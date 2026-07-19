package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTaskHeuristicsTest {
    private val ithomeSnapshot = StructuredPageSnapshot(
        packageName = "com.ruanmei.ithome",
        appHint = "",
        clickables = listOf("搜索框ll_search", "IT之家Logoiv_logo"),
        editables = emptyList(),
        visibleTexts = listOf("Windows"),
        sendButtons = emptyList(),
        fingerprint = "ithome",
    )

    private val wechatListSnapshot = StructuredPageSnapshot(
        packageName = "com.tencent.mm",
        appHint = "当前为微信",
        clickables = listOf(
            "退出浮窗jxs",
            "搜索小程序 搜索栏md5",
            "吴志强 [语音] 2\"",
            "通讯录",
            "发现",
            "搜索jha",
        ),
        editables = emptyList(),
        visibleTexts = listOf("吴志强", "微信(40)"),
        sendButtons = emptyList(),
        fingerprint = "mm",
    )

    @Test
    fun extractSearchKeyword_fromWindowsCommand() {
        assertEquals(
            "Windows",
            SearchTaskHeuristics.extractSearchKeyword("打开 IT 之家，搜索 Windows 的最新动态"),
        )
    }

    @Test
    fun plannerSupplement_suggestsClickSearchBoxAndType() {
        val hint = SearchTaskHeuristics.plannerSupplement(
            command = "打开 IT 之家，搜索 Windows 的最新动态",
            snapshot = ithomeSnapshot,
        )
        assertTrue(hint.contains("搜索框"))
        assertTrue(hint.contains("click"))
        assertTrue(hint.contains("type"))
        assertTrue(hint.contains("Windows"))
        assertTrue(hint.contains("勿盲目 tap"))
    }

    @Test
    fun plannerSupplement_wechatSendPrefersContactRowNotMiniProgramSearch() {
        val hint = SearchTaskHeuristics.plannerSupplement(
            command = "去微信给吴志强发消息说你是个猪",
            snapshot = wechatListSnapshot,
        )
        assertTrue(hint.contains("吴志强"))
        assertTrue(hint.contains("直接 click"))
        assertTrue(hint.contains("禁止"))
        assertFalse(hint.contains("【搜索提示】"))
        assertFalse(hint.contains("请 click 该搜索框"))
    }

    @Test
    fun findSearchBoxLabel_skipsMiniProgramSearch() {
        val label = SearchTaskHeuristics.findSearchBoxLabel(wechatListSnapshot)
        assertEquals("搜索jha", label)
    }

    @Test
    fun extractImContact_fromWechatCommand() {
        assertEquals(
            "吴志强",
            SearchTaskHeuristics.extractImContact("去微信给吴志强发消息说你是个猪"),
        )
    }

    @Test
    fun postStepNudge_afterRepeatedTaps_suggestsClickAndType() {
        val steps = (1..2).map { i ->
            AgentStepRecord(
                step = i,
                action = AgentAction(action = "tap", targetText = "500,150"),
                result = ActionExecutionResult(success = true, summary = "ok"),
                pageDiff = "",
            )
        }
        val hint = SearchTaskHeuristics.postStepNudge(
            command = "打开 IT 之家，搜索 Windows 的最新动态",
            steps = steps,
            snapshot = ithomeSnapshot,
            lastAction = AgentAction(action = "tap", targetText = "500,150"),
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("click"))
        assertTrue(hint.contains("Windows"))
    }

    @Test
    fun postStepNudge_afterClickSearchBox_suggestsType() {
        val hint = SearchTaskHeuristics.postStepNudge(
            command = "搜索 Windows",
            steps = listOf(
                AgentStepRecord(
                    step = 1,
                    action = AgentAction(action = "click", targetText = "搜索框"),
                    result = ActionExecutionResult(success = true, summary = "ok"),
                    pageDiff = "",
                ),
            ),
            snapshot = ithomeSnapshot,
            lastAction = AgentAction(action = "click", targetText = "搜索框"),
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("type"))
        assertTrue(hint.contains("Windows"))
    }
}
