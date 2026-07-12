package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionOnlyAppsTest {
    @Test
    fun isVisionOnly_weChatAndQq() {
        assertTrue(VisionOnlyApps.isVisionOnly(AppHintStore.PKG_WECHAT))
        assertTrue(VisionOnlyApps.isVisionOnly(AppHintStore.PKG_QQ))
        assertFalse(VisionOnlyApps.isVisionOnly("com.android.settings"))
    }
}
