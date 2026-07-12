package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReadinessTest {
    @Test
    fun isReadable_trueWhenClickablesPresent() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mm",
            appHint = "当前为微信",
            clickables = listOf("搜索"),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "x",
        )
        assertTrue(PageReadiness.isReadable(snapshot))
    }

    @Test
    fun isReadable_falseWhenEmpty() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mm",
            appHint = "",
            clickables = emptyList(),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "x",
        )
        assertFalse(PageReadiness.isReadable(snapshot))
    }

    @Test
    fun isWrongChromeTree_detectsStatusBarAnimation() {
        assertTrue(
            PageReadiness.isWrongChromeTree(
                "id=\"status_bar_launch_animation_container\"",
            ),
        )
    }

    @Test
    fun isEmptyTreeSnippet_detectsZeroNodes() {
        assertTrue(PageReadiness.isEmptyTreeSnippet("=== 结构树(节选, 0 节点) ===\n(无结构节点)"))
        assertFalse(PageReadiness.isEmptyTreeSnippet("=== 结构树(节选, 3 节点) ===\n- [0] FrameLayout"))
    }

    @Test
    fun needsVisionFallback_trueWhenTreeEmpty() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mm",
            appHint = "",
            clickables = emptyList(),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "x",
        )
        assertTrue(PageReadiness.needsVisionFallback(snapshot))
        assertTrue(PageReadiness.needsVisionFallback(null))
    }

    @Test
    fun needsVisionFallback_falseWhenReadable() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.bilibili.app.in",
            appHint = "",
            clickables = listOf("搜索"),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "x",
        )
        assertFalse(PageReadiness.needsVisionFallback(snapshot))
    }
}
