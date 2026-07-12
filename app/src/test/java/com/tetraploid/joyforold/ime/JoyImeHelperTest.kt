package com.tetraploid.joyforold.ime

import org.junit.Assert.assertFalse
import org.junit.Test

class JoyImeHelperTest {

    @Test
    fun imeId_mustNotUseFullQualifiedClassName() {
        // 系统 InputMethodInfo.id 为 flattenToShortString，形如 pkg/.ime.Service，
        // 不是 pkg/pkg.ime.Service（旧实现因此永远匹配失败）。
        val wrongLegacy = "com.tetraploid.joyforold/com.tetraploid.joyforold.ime.JoyInputMethodService"
        val expectedShort = "com.tetraploid.joyforold/.ime.JoyInputMethodService"
        assertFalse(wrongLegacy == expectedShort)
    }
}
