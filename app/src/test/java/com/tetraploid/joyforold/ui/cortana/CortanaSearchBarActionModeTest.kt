package com.tetraploid.joyforold.ui.cortana

import org.junit.Assert.assertEquals
import org.junit.Test

class CortanaSearchBarActionModeTest {
    @Test
    fun idleEmpty_showsMic() {
        assertEquals(
            InputActionMode.Mic,
            resolveInputActionMode(value = "", isListening = false, isRunning = false),
        )
    }

    @Test
    fun hasText_showsSend() {
        assertEquals(
            InputActionMode.Send,
            resolveInputActionMode(value = "打开设置", isListening = false, isRunning = false),
        )
    }

    @Test
    fun running_showsCancel() {
        assertEquals(
            InputActionMode.Cancel,
            resolveInputActionMode(value = "打开设置", isListening = false, isRunning = true),
        )
    }

    @Test
    fun listeningWithoutText_showsCancel() {
        assertEquals(
            InputActionMode.Cancel,
            resolveInputActionMode(value = "", isListening = true, isRunning = false),
        )
    }

    @Test
    fun listeningWithText_showsSend() {
        assertEquals(
            InputActionMode.Send,
            resolveInputActionMode(value = "你好", isListening = true, isRunning = false),
        )
    }

    @Test
    fun voiceBusyWithoutText_showsCancel() {
        assertEquals(
            InputActionMode.Cancel,
            resolveInputActionMode(
                value = "",
                isListening = false,
                isRunning = false,
                voiceBusy = true,
            ),
        )
    }
}
