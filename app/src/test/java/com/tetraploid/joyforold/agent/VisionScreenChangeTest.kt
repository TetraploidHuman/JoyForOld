package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionScreenChangeTest {
    @Test
    fun fingerprint_differs_when_screenshot_changes() {
        val a = VisionScreenChange.fingerprint("a".repeat(1000))
        val b = VisionScreenChange.fingerprint("b".repeat(1000))
        assertTrue(a != null && b != null && a != b)
    }

    @Test
    fun augmentPageDiff_marks_screenshot_changed() {
        val prev = VisionScreenChange.fingerprint("screen1".repeat(200))
        val curr = VisionScreenChange.fingerprint("screen2".repeat(200))
        val diff = VisionScreenChange.augmentPageDiff(
            "页面指纹未变（可能仍在同一屏或变化较小）",
            prev,
            curr,
        )
        assertTrue(diff.contains(VisionScreenChange.CHANGED_MARKER))
        assertFalse(VisionScreenChange.indicatesNoProgress(diff))
        assertFalse(AgentActionGuard.pageDiffIndicatesNoChange(diff))
    }

    @Test
    fun augmentPageDiff_marks_screenshot_unchanged() {
        val fp = VisionScreenChange.fingerprint("same".repeat(200))
        val diff = VisionScreenChange.augmentPageDiff(
            "页面指纹未变（可能仍在同一屏或变化较小）",
            fp,
            fp,
        )
        assertTrue(diff.contains(VisionScreenChange.UNCHANGED_MARKER))
        assertTrue(VisionScreenChange.indicatesNoProgress(diff))
    }
}
