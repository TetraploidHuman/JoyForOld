package com.tetraploid.joyforold.offline.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDateTime

class TimeSlotParserTest {
    @Test
    fun parse_clockFormat() {
        val parsed = TimeSlotParser.parse("设闹钟7:30")
        assertNotNull(parsed)
        assertEquals("7:30", parsed!!.hhmm)
    }

    @Test
    fun parse_chineseAfternoon() {
        val parsed = TimeSlotParser.parse("下午3点提醒我")
        assertNotNull(parsed)
        assertEquals("15:00", parsed!!.hhmm)
    }

    @Test
    fun parse_halfHour() {
        val parsed = TimeSlotParser.parse("晚上八点半闹钟")
        assertNotNull(parsed)
        assertEquals("20:30", parsed!!.hhmm)
    }
}
