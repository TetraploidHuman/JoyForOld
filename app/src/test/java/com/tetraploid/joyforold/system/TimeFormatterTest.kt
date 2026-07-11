package com.tetraploid.joyforold.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TimeFormatterTest {
    @Test
    fun spokenNow_includesPeriodAndWeekday() {
        val spoken = TimeFormatter.spokenNow(LocalDateTime.of(2026, 7, 11, 15, 25))
        assertTrue(spoken.contains("下午"))
        assertTrue(spoken.contains("3点25分"))
        assertTrue(spoken.contains("7月11日"))
        assertTrue(spoken.contains("星期六"))
    }
}
