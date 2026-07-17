package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PageContextSelectorTest {
    @Test
    fun modeFor_firstObservation_returnsFull() {
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(null, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(null, current, diff),
        )
    }

    @Test
    fun modeFor_fingerprintUnchanged_returnsDiffOnly() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.DIFF_ONLY,
            PageContextSelector.modeFor(previous, current, diff),
        )
    }

    @Test
    fun modeFor_minorChange_returnsCompact() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = previous.copy(
            fingerprint = "fp2",
            clickables = previous.clickables + "新按钮",
        )
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.COMPACT,
            PageContextSelector.modeFor(previous, current, diff),
        )
    }

    @Test
    fun modeFor_packageChanged_returnsFull() {
        val previous = snapshot(pkg = "com.tencent.mobileqq", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp9", clickables = 8)
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(previous, current, diff),
        )
    }

    @Test
    fun modeFor_forceFull_overridesDiffOnly() {
        val previous = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(
                previous = previous,
                current = current,
                pageDiff = diff,
                forceFull = true,
            ),
        )
    }

    @Test
    fun modeFor_stepsSinceLastFull_forcesRefresh() {
        val previous = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(
                previous = previous,
                current = current,
                pageDiff = diff,
                stepsSinceLastFull = PageContextSelector.FULL_REFRESH_EVERY_N_STEPS,
            ),
        )
    }

    @Test
    fun modeFor_a11yUnavailable_returnsFull() {
        val previous = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val current = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(
                previous = previous,
                current = current,
                pageDiff = diff,
                a11yUnavailable = true,
            ),
        )
    }

    @Test
    fun modeFor_majorChange_returnsFull() {
        val previous = snapshot(pkg = "com.tencent.mm", fingerprint = "fp1", clickables = 5)
        val current = previous.copy(
            fingerprint = "fp9",
            clickables = List(12) { "控件$it" },
            visibleTexts = List(20) { "文字$it" },
        )
        val diff = PageObservation.diff(previous, current)

        assertEquals(
            PageContextMode.FULL,
            PageContextSelector.modeFor(previous, current, diff),
        )
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
