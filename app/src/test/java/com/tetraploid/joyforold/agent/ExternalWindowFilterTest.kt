package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWindowFilterTest {
    @Test
    fun ignoresSystemUiPackage() {
        assertTrue(ExternalWindowFilter.isIgnoredPackage("com.android.systemui"))
    }

    @Test
    fun detectsLaunchAnimationTree() {
        val snippet = """
            === 结构树(节选, 31 节点) ===
            - [0] FrameLayout @bottom
              - [0.0] FrameLayout @bottom id="status_bar_launch_animation_container"
        """.trimIndent()
        assertTrue(ExternalWindowFilter.isSystemChromeTreeSnippet(snippet))
    }

    @Test
    fun rejectsStatusBarOnlySnapshot() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.android.systemui",
            appHint = "",
            clickables = emptyList(),
            editables = emptyList(),
            visibleTexts = listOf("18:39", "Android 系统通知：已连接"),
            sendButtons = emptyList(),
            fingerprint = "com.android.systemui|0|0|18:39",
        )
        assertTrue(ExternalWindowFilter.isSystemChromeSnapshot(snapshot))
        assertFalse(PageReadiness.isReadable(snapshot, expectedPackage = "com.tencent.mm"))
    }

    @Test
    fun acceptsWeChatLikeSnapshot() {
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mm",
            appHint = "当前为微信",
            clickables = listOf("搜索", "微信"),
            editables = emptyList(),
            visibleTexts = listOf("大女儿"),
            sendButtons = emptyList(),
            fingerprint = "com.tencent.mm|2|0|大女儿",
        )
        assertFalse(ExternalWindowFilter.isSystemChromeSnapshot(snapshot))
        assertTrue(PageReadiness.isReadable(snapshot, expectedPackage = "com.tencent.mm"))
    }
}
