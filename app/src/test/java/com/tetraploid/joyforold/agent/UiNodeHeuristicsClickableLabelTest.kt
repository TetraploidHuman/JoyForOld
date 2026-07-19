package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodeHeuristicsClickableLabelTest {

    @Test
    fun composeClickableLabel_keepsOwnHumanText() {
        assertEquals(
            "搜索jha",
            UiNodeHeuristics.composeClickableLabel(
                ownHumanLabel = "搜索",
                ownViewId = "jha",
                ownClassName = "RelativeLayout",
                descendantHumanLabels = listOf("吴志强"),
            ),
        )
    }

    @Test
    fun composeClickableLabel_aggregatesChildNamesWhenOnlyViewId() {
        assertEquals(
            "吴志强",
            UiNodeHeuristics.composeClickableLabel(
                ownHumanLabel = "",
                ownViewId = "cj1",
                ownClassName = "LinearLayout",
                descendantHumanLabels = listOf("吴志强", "晚上7:16", "猪"),
            ),
        )
    }

    @Test
    fun composeClickableLabel_searchResultRowUsesChildName() {
        assertEquals(
            "吴志强",
            UiNodeHeuristics.composeClickableLabel(
                ownHumanLabel = "",
                ownViewId = "",
                ownClassName = "RelativeLayout",
                descendantHumanLabels = listOf("吴志强"),
            ),
        )
    }

    @Test
    fun composeClickableLabel_fallsBackToViewId() {
        assertEquals(
            "cj1",
            UiNodeHeuristics.composeClickableLabel(
                ownHumanLabel = "",
                ownViewId = "cj1",
                ownClassName = "LinearLayout",
                descendantHumanLabels = emptyList(),
            ),
        )
    }

    @Test
    fun joinDescendantLabels_skipsTimestampsAndCrypticIds() {
        assertEquals(
            "吴志强 猪",
            UiNodeHeuristics.joinDescendantLabels(
                listOf("kbq", "吴志强", "晚上7:16", "猪", "昨天"),
            ),
        )
    }

    @Test
    fun composeClickableLabel_wechatChatRowLikeNesting() {
        // 模拟会话行：自身无文案，子树深处才有联系人名（与 UI 树 cj1→…→kbq 一致）
        assertEquals(
            "吴志强",
            UiNodeHeuristics.composeClickableLabel(
                ownHumanLabel = "",
                ownViewId = "cj1",
                ownClassName = "LinearLayout",
                descendantHumanLabels = listOf("吴志强", "晚上7:16", "猪"),
            ),
        )
    }

    @Test
    fun looksLikeCrypticId_matchesWeChatObfuscatedIds() {
        assertTrue(UiNodeHeuristics.looksLikeCrypticId("cj1"))
        assertTrue(UiNodeHeuristics.looksLikeCrypticId("jha"))
        assertTrue(UiNodeHeuristics.looksLikeCrypticId("d98"))
        assertFalse(UiNodeHeuristics.looksLikeCrypticId("吴志强"))
        assertFalse(UiNodeHeuristics.looksLikeCrypticId("搜索"))
    }
}
