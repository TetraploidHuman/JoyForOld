package com.tetraploid.joyforold.agent

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppHintStoreTest {
    @Test
    fun seeds_and_stores_hints() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = AppHintStore(context)
        store.ensureSeededDefaults()
        val wechat = store.formatForPrompt(AppHintStore.PKG_WECHAT)
        assertTrue(wechat.contains("本应用经验"))
        store.addHint(AppHintStore.PKG_WECHAT, "曾通过 click「搜索」完成操作")
        assertTrue(store.hintsFor(AppHintStore.PKG_WECHAT).any { it.contains("搜索") })
    }
}
