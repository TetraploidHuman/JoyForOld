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
    fun parse_navigateTo_nearestKfc_offlineDefaultPick() {
        // 限定词语义交给 AI；本地离线兜底统一 navigate_pick
        val steps = SystemIntentLocalParser.parse("带我去最近的肯德基")
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertTrue(steps.none { it.action == "finish" })
    }

    @Test
    fun parse_navigateTo_typoNearestKfcEnglish() {
        val steps = SystemIntentLocalParser.parse("带我去最进的kfc")
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
    }

    @Test
    fun normalizePoiQuery_mapsEnglishBrands() {
        assertEquals("肯德基", SystemIntentLocalParser.normalizePoiQuery("kfc"))
        assertEquals("麦当劳", SystemIntentLocalParser.normalizePoiQuery("McDonald"))
        assertEquals("公园", SystemIntentLocalParser.normalizePoiQuery("公园"))
    }

    @Test
    fun parse_navigateTo_nearbyPark_offlineDefaultPick() {
        val steps = SystemIntentLocalParser.parse("我要去附近的公园。")
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.first().action)
        assertEquals("公园", steps.first().targetText)
    }

    @Test
    fun parse_navigateTo_namedSchool_asksUserToPick() {
        val steps = SystemIntentLocalParser.parse("带我去桂阳一中")
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.first().action)
        assertEquals("桂阳一中", steps.first().targetText)
        assertTrue(steps.none { it.action == "finish" })
        assertTrue(steps.none { it.action == AgentActionSet.ACTION_RUN_ACTION_SET })
    }

    @Test
    fun parse_navigate_nearLandmark_kfc() {
        val steps = SystemIntentLocalParser.parse("带我去桂阳一中附近的KFC")
        assertNotNull(steps)
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertEquals("桂阳一中", steps.first().inputText)
        assertEquals("finish", steps[1].action)
    }

    @Test
    fun parse_navigate_nearLandmark_asrTypo附件() {
        val steps = SystemIntentLocalParser.parse("带我去桂阳一中附件的麦当劳")
        assertNotNull(steps)
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("麦当劳", steps.first().targetText)
        assertEquals("桂阳一中", steps.first().inputText)
    }

    @Test
    fun parse_navigate_nearLandmark_station() {
        val steps = SystemIntentLocalParser.parse("导航到郴州高铁站附近的麦当劳")
        assertNotNull(steps)
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("麦当劳", steps.first().targetText)
        assertEquals("郴州高铁站", steps.first().inputText)
    }

    @Test
    fun parse_navigate_nearLandmark_chenzhouYizhongKfc() {
        val steps = SystemIntentLocalParser.parse("我要去郴州市一中附近的kfc")
        assertNotNull(steps)
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertEquals("郴州市一中", steps.first().inputText)
    }

    @Test
    fun parse_navigate_adminRegion_beihuKfc() {
        val steps = SystemIntentLocalParser.parse("带我去郴州市北湖区的肯德基")
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertEquals("郴州市北湖区", steps.first().inputText)
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
