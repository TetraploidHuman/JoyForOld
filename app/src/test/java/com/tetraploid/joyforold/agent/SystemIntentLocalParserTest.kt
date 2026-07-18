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
    fun parse_navigateDestination_leftToLlm() {
        // navigate_to / navigate_pick 不再走本地正则路由
        assertNull(SystemIntentLocalParser.parse("带我去最近的肯德基"))
        assertNull(SystemIntentLocalParser.parse("带我去最进的kfc"))
        assertNull(SystemIntentLocalParser.parse("我要去附近的公园。"))
        assertNull(SystemIntentLocalParser.parse("带我去桂阳一中"))
        assertNull(SystemIntentLocalParser.parse("带我去桂阳一中附近的KFC"))
        assertNull(SystemIntentLocalParser.parse("导航到郴州高铁站附近的麦当劳"))
        assertNull(SystemIntentLocalParser.parse("带我去郴州市北湖区的肯德基"))
    }

    @Test
    fun normalizePoiQuery_mapsEnglishBrands() {
        assertEquals("肯德基", SystemIntentLocalParser.normalizePoiQuery("kfc"))
        assertEquals("麦当劳", SystemIntentLocalParser.normalizePoiQuery("McDonald"))
        assertEquals("公园", SystemIntentLocalParser.normalizePoiQuery("公园"))
    }

    @Test
    fun softNormalize_fixesAsr附件的() {
        assertEquals(
            "带我去桂阳一中附近的麦当劳",
            SystemIntentLocalParser.softNormalizeNavigateUtterance("带我去桂阳一中附件的麦当劳"),
        )
    }

    @Test
    fun splitNearLandmarkQuery_kfc() {
        val near = SystemIntentLocalParser.splitNearLandmarkQuery("桂阳一中附近的KFC")
        assertNotNull(near)
        assertEquals("桂阳一中", near!!.landmark)
        assertEquals("肯德基", near.poi)
    }

    @Test
    fun splitNearLandmarkQuery_station() {
        val near = SystemIntentLocalParser.splitNearLandmarkQuery("郴州高铁站附近的麦当劳")
        assertNotNull(near)
        assertEquals("郴州高铁站", near!!.landmark)
        assertEquals("麦当劳", near.poi)
    }

    @Test
    fun splitScopedPoiQuery_chenzhouYizhongKfc() {
        val near = SystemIntentLocalParser.splitScopedPoiQuery("郴州市一中附近的kfc")
        assertNotNull(near)
        assertEquals("郴州市一中", near!!.landmark)
        assertEquals("肯德基", near.poi)
    }

    @Test
    fun splitAdminRegionQuery_beihu() {
        val near = SystemIntentLocalParser.splitAdminRegionQuery("郴州市北湖区的肯德基")
        assertNotNull(near)
        assertEquals("郴州市北湖区", near!!.landmark)
        assertEquals("肯德基", near.poi)
    }

    @Test
    fun looksLikeSpecificAddress_doesNotTreatAdminPlusBrand() {
        assertTrue(!SystemIntentLocalParser.looksLikeSpecificAddressQuery("郴州市北湖区的肯德基"))
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
    fun parse_goToTimSendMessage_isNotNavigate() {
        assertNull(SystemIntentLocalParser.parse("去tim给三八老大发消息说你是猪"))
        assertNull(SystemIntentLocalParser.parse("去TIM给三八老大发消息说你是猪"))
        assertNull(SystemIntentLocalParser.parse("去微信给儿子发消息说今晚回家"))
        assertNull(SystemIntentLocalParser.parse("去qq给老王说一声到了"))
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
