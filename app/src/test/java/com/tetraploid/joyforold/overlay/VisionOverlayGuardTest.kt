package com.tetraploid.joyforold.overlay

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.StructuredPageSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionOverlayGuardTest {
    @Test
    fun needs_hidden_overlay_for_tap_in_vision_fallback() {
        val snapshot = wechatEmptyTree()
        assertTrue(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "tap", targetText = "450,950"),
                snapshot,
            ),
        )
    }

    @Test
    fun skips_open_app_and_readable_snapshot_tap() {
        val readable = StructuredPageSnapshot(
            packageName = "com.android.settings",
            appHint = "",
            clickables = listOf("WLAN", "蓝牙"),
            editables = emptyList(),
            visibleTexts = emptyList(),
            sendButtons = emptyList(),
            fingerprint = "x",
        )
        assertFalse(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "open_app", targetText = "微信"),
                readable,
            ),
        )
        assertFalse(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "tap", targetText = "500,500"),
                readable,
            ),
        )
    }

    @Test
    fun a11y_click_always_hides_overlay_even_when_tree_readable() {
        val readable = StructuredPageSnapshot(
            packageName = "com.autonavi.minimap",
            appHint = "当前为高德地图",
            clickables = listOf("导航", "路线"),
            editables = emptyList(),
            visibleTexts = listOf("导航", "路线"),
            sendButtons = emptyList(),
            fingerprint = "amap",
        )
        assertTrue(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "click", targetText = "导航"),
                readable,
            ),
        )
    }

    @Test
    fun type_and_send_need_hidden_overlay_when_a11y_unavailable() {
        val snapshot = wechatEmptyTree()
        assertTrue(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "type", inputText = "你好"),
                snapshot,
            ),
        )
        assertTrue(
            VisionOverlayGuard.actionNeedsHiddenOverlay(
                AgentAction(action = "send"),
                snapshot,
            ),
        )
    }

    private fun wechatEmptyTree() = StructuredPageSnapshot(
        packageName = "com.tencent.mm",
        appHint = "当前为微信",
        clickables = emptyList(),
        editables = emptyList(),
        visibleTexts = emptyList(),
        sendButtons = emptyList(),
        fingerprint = "x",
    )
}
