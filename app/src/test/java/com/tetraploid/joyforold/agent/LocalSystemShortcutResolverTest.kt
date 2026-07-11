package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSystemShortcutResolverTest {
    @Test
    fun matchSettingsShortcut_wifi() {
        val match = LocalSystemShortcutResolver.matchSettingsShortcut("打开 WiFi")
        assertNotNull(match)
        assertEquals("open_wifi_settings", match!!.steps.first().action)
    }

    @Test
    fun matchSettingsShortcut_bluetooth() {
        val match = LocalSystemShortcutResolver.matchSettingsShortcut("开蓝牙")
        assertNotNull(match)
        assertEquals("open_bluetooth_settings", match!!.steps.first().action)
    }

    @Test
    fun matchSettingsShortcut_settings() {
        val match = LocalSystemShortcutResolver.matchSettingsShortcut("打开设置")
        assertNotNull(match)
        assertEquals("open_settings", match!!.steps.first().action)
    }

    @Test
    fun matchSettingsShortcut_unknownReturnsNull() {
        assertNull(LocalSystemShortcutResolver.matchSettingsShortcut("今天天气怎么样"))
    }

    @Test
    fun isSystemIntentOnly_localShortcutRoute() {
        val match = LocalSystemShortcutResolver.matchSettingsShortcut("打开蓝牙")!!
        assertEquals(true, AgentToolRegistry.isSystemIntentOnly(match.steps))
    }
}
