package com.tetraploid.joyforold.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class PageObservationDiffTest {
    @Test
    fun diff_firstObservation() {
        val current = sampleSnapshot(
            packageName = "com.tencent.mobileqq",
            clickables = listOf("联系人", "消息"),
        )
        val diff = PageObservation.diff(null, current)
        assertTrue(diff.contains("首次观察"))
        assertTrue(diff.contains("com.tencent.mobileqq"))
    }

    @Test
    fun diff_detectsNewClickables() {
        val previous = sampleSnapshot(clickables = listOf("联系人"))
        val current = sampleSnapshot(clickables = listOf("联系人", "语音通话"))
        val diff = PageObservation.diff(previous, current)
        assertTrue(diff.contains("新增可点击"))
        assertTrue(diff.contains("语音通话"))
    }

    @Test
    fun diff_detectsPackageChange() {
        val previous = sampleSnapshot(packageName = "com.android.launcher")
        val current = sampleSnapshot(packageName = "com.tencent.mobileqq")
        val diff = PageObservation.diff(previous, current)
        assertTrue(diff.contains("应用切换"))
    }

    private fun sampleSnapshot(
        packageName: String = "com.example.app",
        clickables: List<String> = emptyList(),
    ): StructuredPageSnapshot {
        return StructuredPageSnapshot(
            packageName = packageName,
            appHint = "",
            clickables = clickables,
            editables = emptyList(),
            visibleTexts = clickables,
            sendButtons = emptyList(),
            fingerprint = "$packageName|${clickables.joinToString()}",
        )
    }
}
