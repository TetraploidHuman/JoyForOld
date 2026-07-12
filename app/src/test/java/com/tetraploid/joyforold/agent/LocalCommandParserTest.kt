package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserTest {
    @Test
    fun parse_sendSms_viaSystemIntentParser() {
        val steps = LocalCommandParser.parse("给女儿发短信：我到了")
        assertNotNull(steps)
        assertEquals("send_sms", steps!!.first().action)
    }

    @Test
    fun parse_dialContact_viaSystemIntentParser() {
        val steps = LocalCommandParser.parse("打电话给女儿")
        assertNotNull(steps)
        assertEquals("dial_contact", steps!!.first().action)
    }

    @Test
    fun parse_clickCommand() {
        val steps = LocalCommandParser.parse("点击设置")
        assertNotNull(steps)
        assertEquals("click", steps!!.first().action)
        assertEquals("设置", steps.first().targetText)
    }

    @Test
    fun parse_backCommand() {
        val steps = LocalCommandParser.parse("返回")
        assertNotNull(steps)
        assertEquals("back", steps!!.first().action)
    }

    @Test
    fun parse_sendToPerson_deferredToAgent() {
        assertNull(LocalCommandParser.parse("给张三发消息：你好"))
    }

    @Test
    fun parse_timeQuery() {
        val steps = LocalCommandParser.parse("几点了")
        assertNotNull(steps)
        assertEquals("tell_time", steps!!.first().action)
    }

    @Test
    fun parse_timeQuery_colloquial() {
        val steps = LocalCommandParser.parse("现在几点钟了")
        assertNotNull(steps)
        assertEquals("tell_time", steps!!.first().action)
    }

    @Test
    fun planFromCommand_timeQuery_notClick() {
        val phases = TaskPhasePlanner.planFromCommand("现在几点钟了")
        assertTrue(phases.any { it.label.contains("查看时间") })
        assertFalse(phases.any { it.label.contains("钟了") })
    }

    @Test
    fun parse_weatherQuery() {
        val steps = LocalCommandParser.parse("帮我查一下天气")
        assertNotNull(steps)
        assertEquals("query_weather", steps!!.first().action)
    }

    @Test
    fun parse_unknownReturnsNull() {
        assertNull(LocalCommandParser.parse("随便说点什么"))
    }

    @Test
    fun isSendToSpecificPerson_detectsPattern() {
        assertTrue(LocalCommandParser.isSendToSpecificPerson("给610发消息：测试"))
        assertTrue(!LocalCommandParser.isSendToSpecificPerson("发送：你好"))
    }
}
