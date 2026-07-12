package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemIntentLocalParserTest {
    @Test
    fun parse_dialContact() {
        val steps = SystemIntentLocalParser.parse("给女儿打电话")
        assertNotNull(steps)
        assertEquals("dial_contact", steps!!.first().action)
        assertEquals("女儿", steps.first().targetText)
        assertTrue(steps.last().needsBinaryConfirm)
    }

    @Test
    fun parse_sendSms() {
        val steps = SystemIntentLocalParser.parse("给女儿发短信：我到了")
        assertNotNull(steps)
        assertEquals("send_sms", steps!!.first().action)
        assertEquals("女儿", steps.first().targetText)
        assertEquals("我到了", steps.first().inputText)
    }

    @Test
    fun parse_setAlarm_withTime() {
        val steps = SystemIntentLocalParser.parse("7:30叫我")
        assertNotNull(steps)
        assertEquals("set_alarm", steps!!.first().action)
        assertEquals("7:30", steps.first().targetText)
    }

    @Test
    fun parse_setAlarm_withoutTime_asksUser() {
        val steps = SystemIntentLocalParser.parse("设个闹钟")
        assertNotNull(steps)
        assertTrue(steps!!.single().waitingForUser)
    }

    @Test
    fun parse_addCalendarEvent() {
        val steps = SystemIntentLocalParser.parse("记一下明天开会")
        assertNotNull(steps)
        assertEquals("add_calendar_event", steps!!.first().action)
        assertEquals("开会", steps.first().targetText)
    }

    @Test
    fun parse_openWifiSettings() {
        val steps = SystemIntentLocalParser.parse("打开蓝牙")
        assertNotNull(steps)
        assertEquals("open_bluetooth_settings", steps!!.first().action)
    }

    @Test
    fun parse_openFontSettings() {
        val steps = SystemIntentLocalParser.parse("放大字体")
        assertNotNull(steps)
        assertEquals("open_font_settings", steps!!.first().action)
    }

    @Test
    fun parse_openCamera() {
        val steps = SystemIntentLocalParser.parse("拍照")
        assertNotNull(steps)
        assertEquals("open_camera", steps!!.first().action)
    }

    @Test
    fun parse_openGallery() {
        val steps = SystemIntentLocalParser.parse("打开相册")
        assertNotNull(steps)
        assertEquals("open_gallery", steps!!.first().action)
    }

    @Test
    fun parse_navigateHome() {
        val steps = SystemIntentLocalParser.parse("导航回家")
        assertNotNull(steps)
        assertEquals("navigate_home", steps!!.first().action)
    }

    @Test
    fun parse_openApp_withoutContext() {
        val steps = SystemIntentLocalParser.parse("打开微信")
        assertNotNull(steps)
        assertEquals("open_app", steps!!.first().action)
        assertEquals("微信", steps.first().targetText)
    }

    @Test
    fun parse_unknownReturnsNull() {
        assertNull(SystemIntentLocalParser.parse("随便聊聊"))
    }

    @Test
    fun isSystemIntentOnly_allParsedRoutes() {
        val samples = listOf(
            "给女儿打电话",
            "7:30叫我",
            "打开设置",
            "拍照",
            "导航回家",
        )
        for (command in samples) {
            val steps = SystemIntentLocalParser.parse(command)
            assertNotNull(command, steps)
            assertTrue("$command should be system-intent-only", SystemIntentLocalParser.isSystemIntentOnly(steps!!))
        }
    }
}
