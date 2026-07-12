package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PageContextSelectorTest {
    @Test
    fun modeFor_alwaysReturnsFull_forEffectPriority() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(previous, current)

        assertEquals(PageContextMode.FULL, PageContextSelector.modeFor(previous, current, diff))
    }

    @Test
    fun modeFor_minorChange_returnsFull() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = previous.copy(
            fingerprint = "fp2",
            clickables = previous.clickables + "新按钮",
        )
        val diff = PageObservation.diff(previous, current)

        assertEquals(PageContextMode.FULL, PageContextSelector.modeFor(previous, current, diff))
    }

    @Test
    fun modeFor_packageChanged_returnsFull() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp9", clickables = 8)
        val diff = PageObservation.diff(previous, current)

        assertEquals(PageContextMode.FULL, PageContextSelector.modeFor(previous, current, diff))
    }

    private fun snapshot(
        pkg: String,
        fingerprint: String,
        clickables: Int,
    ): StructuredPageSnapshot {
        return StructuredPageSnapshot(
            packageName = pkg,
            appHint = "",
            clickables = List(clickables) { "按钮$it" },
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = fingerprint,
        )
    }
}
