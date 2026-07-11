package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoQueryDetectorTest {
    @Test
    fun isTimeQuery_recognizesColloquialPhrases() {
        assertTrue(InfoQueryDetector.isTimeQuery("现在几点钟了"))
        assertTrue(InfoQueryDetector.isTimeQuery("几点了"))
        assertFalse(InfoQueryDetector.isTimeQuery("点击设置"))
    }

    @Test
    fun isWeatherQuery_recognizesWeatherPhrases() {
        assertTrue(InfoQueryDetector.isWeatherQuery("今天天气怎么样"))
        assertFalse(InfoQueryDetector.isWeatherQuery("打开天气"))
    }
}
