package com.tetraploid.joyforold.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class JoyImeHelperTest {

    @Test
    fun imeId_usesFullServiceClassName() {
        assertEquals(
            "com.tetraploid.joyforold/com.tetraploid.joyforold.ime.JoyInputMethodService",
            JoyImeHelper.imeId("com.tetraploid.joyforold"),
        )
    }
}
