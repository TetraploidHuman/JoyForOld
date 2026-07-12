package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPageContextTest {
    @Test
    fun pageContext_omitsEmptyA11yLists() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.example.app",
            appHint = "【本应用经验】某经验",
            clickables = emptyList(),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "fp",
        )
        val context = VisionPageContext.formatPageContext(snapshot, hasScreenshot = true)
        assertTrue(context.contains("视觉观察"))
        assertTrue(context.contains("com.example.app"))
        assertFalse(context.contains("可点击(0)"))
        assertFalse(context.contains("可输入(0)"))
        assertFalse(context.contains("可见文字(0)"))
    }

    @Test
    fun pageDiff_usesVisionMarkersNotA11yFingerprint() {
        val diff = VisionPageContext.formatPageDiff(
            packageName = "com.example.app",
            previousSnapshot = null,
            previousVisionFingerprint = null,
            currentVisionFingerprint = "abc",
        )
        assertTrue(diff.contains("首次观察"))
        assertTrue(diff.contains(VisionScreenChange.CHANGED_MARKER) ||
            diff.contains("【视觉】已附带截图"))
        assertFalse(diff.contains("可点击 0 项"))
    }

    @Test
    fun plannerVisionMode_whenA11yUnavailable() {
        val payload = PageObservationPayload(
            pageContext = "",
            pageDiff = "",
            minimalPageContext = "",
            mode = PageContextMode.FULL,
            visionMode = false,
            a11yUnavailable = true,
        )
        assertTrue(payload.plannerVisionMode())
    }
}
