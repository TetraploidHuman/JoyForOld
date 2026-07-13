package com.tetraploid.joyforold.offline.nlu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OfflineNluRouterTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun match_blankCommand_returnsNull() = runTest {
        assertNull(OfflineNluRouter.match("   ", context))
    }

    @Test
    fun match_nullContext_returnsNull() = runTest {
        assertNull(OfflineNluRouter.match("打开蓝牙", null))
    }

    @Test
    fun match_withoutLoadedClassifier_returnsNullOrMatch() = runTest {
        // 未预热模型时 getClassifier 可能为 null；不应抛异常。
        OfflineNluRouter.match("打开蓝牙", context)
    }
}
