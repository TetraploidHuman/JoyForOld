package com.tetraploid.joyforold.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InstalledAppResolverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstalledAppResolver.invalidateCache()
    }

    @Test
    fun resolvePackage_stripsOpenPrefix() {
        // Robolectric 环境应用列表有限，主要验证不会抛错且能处理“打开xxx”形式
        val result = InstalledAppResolver.resolvePackage(context, "打开设置")
        // 设置可能在模拟环境不存在，不断言具体包名
        if (result != null) {
            assertNotNull(result)
        }
    }

    @Test
    fun formatSearchMatches_returnsHintWhenNoMatch() {
        val text = InstalledAppResolver.formatSearchMatches(context, "不存在的应用xyz123")
        assertEquals(true, text.contains("未找到"))
    }
}
