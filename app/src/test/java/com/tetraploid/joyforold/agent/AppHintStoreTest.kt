package com.tetraploid.joyforold.agent

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppHintStoreTest {
    @Test
    fun stores_and_formats_hints_for_any_app() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = AppHintStore(context)
        val packageName = "com.example.custom.app"
        store.addHint(packageName, "曾通过 tap 搜索框完成操作")
        val formatted = store.formatForPrompt(packageName)
        assertTrue(formatted.contains("本应用经验"))
        assertTrue(formatted.contains("tap"))
        store.addHint(packageName, "输入前先 tap 输入框")
        assertTrue(store.hintsFor(packageName).any { it.contains("输入框") })
    }

    @Test
    fun migrates_legacy_click_hints_on_startup_for_any_app() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = AppHintStore(context)
        val packageName = "com.example.legacy.app"
        store.addHint(
            packageName,
            "打开应用后需继续在应用内操作；发消息可先 click 右上角搜索",
        )
        store.ensureSeededDefaults()
        val hints = store.hintsFor(packageName)
        assertTrue(hints.any { it.contains("tap") && it.contains("无障碍树") })
        assertFalse(hints.any { it.contains("click 右上角") })
    }

    @Test
    fun migrates_stale_position_hints_on_startup_for_any_app() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = AppHintStore(context)
        val packageName = "com.example.positioned.app"
        store.addHint(
            packageName,
            "微信屏蔽无障碍树：用 tap 坐标操作；搜索在右上角，会话列表在中部",
        )
        store.ensureSeededDefaults()
        val hints = store.hintsFor(packageName)
        assertTrue(hints.any { it.contains("tap") && it.contains("无障碍树") })
        assertFalse(hints.any { it.contains("右上角") })
    }
}
