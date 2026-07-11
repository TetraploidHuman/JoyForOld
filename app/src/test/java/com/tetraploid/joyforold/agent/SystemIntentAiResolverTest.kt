package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemIntentAiResolverTest {
    @Test
    fun stepsFor_alarm_withTime() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "set_alarm",
                confidence = 0.92,
                timeHhmm = "07:30",
                title = "吃药",
            ),
        )
        assertNotNull(steps)
        assertEquals("set_alarm", steps!!.first().action)
        assertEquals("07:30", steps.first().targetText)
        assertEquals("吃药", steps.first().inputText)
        assertEquals("finish", steps.last().action)
    }

    @Test
    fun stepsFor_alarm_missingTime_asksUser() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "set_alarm",
                confidence = 0.88,
                clarify = "您想设几点的闹钟？",
            ),
        )
        assertNotNull(steps)
        assertEquals("finish", steps!!.single().action)
        assertTrue(steps.single().waitingForUser)
        assertEquals("您想设几点的闹钟？", steps.single().message)
    }

    @Test
    fun stepsFor_calendar_withEventTime() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "add_calendar_event",
                confidence = 0.9,
                title = "开会",
                notes = "和张三",
                eventTimeIso = "2026-07-11T15:00:00+08:00",
            ),
        )
        assertNotNull(steps)
        assertEquals("add_calendar_event", steps!!.first().action)
        assertEquals("开会", steps.first().targetText)
        assertEquals("@t=2026-07-11T15:00:00+08:00|和张三", steps.first().inputText)
    }

    @Test
    fun stepsFor_none_returnsNull() {
        assertNull(
            SystemIntentAiResolver.stepsFor(
                SystemIntentAiResolver.Classification(intent = "none", confidence = 0.2),
            ),
        )
    }

    @Test
    fun encodeCalendarInput_withoutNotes() {
        assertEquals(
            "@t=2026-07-11T15:00:00+08:00",
            SystemIntentAiResolver.encodeCalendarInput(notes = "", eventTimeIso = "2026-07-11T15:00:00+08:00"),
        )
    }

    @Test
    fun isSystemIntentOnly_detectsAlarmRoute() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "set_alarm",
                confidence = 0.9,
                timeHhmm = "08:00",
            ),
        )!!
        assertTrue(AgentToolRegistry.isSystemIntentOnly(steps))
    }
}
