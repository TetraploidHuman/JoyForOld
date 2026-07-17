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

    @Test
    fun stepsFor_navigateTo_direct() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_to",
                confidence = 0.93,
                destination = "肯德基",
            ),
        )
        assertNotNull(steps)
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertEquals("finish", steps.last().action)
    }

    @Test
    fun stepsFor_navigatePick_listsCandidates() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_pick",
                confidence = 0.9,
                destination = "桂阳一中",
            ),
        )
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.single().action)
        assertEquals("桂阳一中", steps.single().targetText)
    }

    @Test
    fun stepsFor_navigateTo_normalizesEnglishBrand() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_to",
                confidence = 0.9,
                destination = "kfc",
            ),
        )
        assertEquals("肯德基", steps!!.first().targetText)
    }

    @Test
    fun stepsFor_navigatePick_withNearLandmark() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_pick",
                confidence = 0.9,
                destination = "肯德基",
                nearLandmark = "桂阳一中",
            ),
        )
        assertNotNull(steps)
        assertEquals("navigate_pick", steps!!.single().action)
        assertEquals("肯德基", steps.single().targetText)
        assertEquals("桂阳一中", steps.single().inputText)
    }

    @Test
    fun stepsFor_navigateTo_splitsCombinedDestination() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_to",
                confidence = 0.9,
                destination = "郴州高铁站附近的麦当劳",
            ),
        )
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("麦当劳", steps.first().targetText)
        assertEquals("郴州高铁站", steps.first().inputText)
        assertTrue(steps.last().message!!.contains("郴州高铁站附近的麦当劳"))
    }

    @Test
    fun stepsFor_navigateTo_trustsAiLandmarkOverLocalOnly() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_to",
                confidence = 0.9,
                destination = "肯德基",
                nearLandmark = "郴州市一中",
            ),
        )
        assertEquals("navigate_to", steps!!.first().action)
        assertEquals("肯德基", steps.first().targetText)
        assertEquals("郴州市一中", steps.first().inputText)
    }

    @Test
    fun stepsFor_navigateTo_aiLandmark_repairsCombinedDestination() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_to",
                confidence = 0.9,
                destination = "郴州市一中附近的kfc",
                nearLandmark = "郴州市一中",
            ),
        )
        assertEquals("肯德基", steps!!.first().targetText)
        assertEquals("郴州市一中", steps.first().inputText)
    }

    @Test
    fun stepsFor_navigatePick_adminRegion() {
        val steps = SystemIntentAiResolver.stepsFor(
            SystemIntentAiResolver.Classification(
                intent = "navigate_pick",
                confidence = 0.9,
                destination = "郴州市北湖区的肯德基",
            ),
        )
        assertEquals("navigate_pick", steps!!.single().action)
        assertEquals("肯德基", steps.single().targetText)
        assertEquals("郴州市北湖区", steps.single().inputText)
    }
}
