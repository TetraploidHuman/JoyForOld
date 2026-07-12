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
