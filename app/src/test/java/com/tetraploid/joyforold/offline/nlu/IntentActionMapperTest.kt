package com.tetraploid.joyforold.offline.nlu

import com.tetraploid.joyforold.agent.AgentToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentActionMapperTest {
    @Test
    fun toSteps_bluetoothIntent() {
        val steps = IntentActionMapper.toSteps("open_bluetooth_settings", "打开蓝牙", context = null)
        assertNotNull(steps)
        assertEquals("open_bluetooth_settings", steps!!.first().action)
        assertTrue(AgentToolRegistry.isSystemIntentOnly(steps))
    }

    @Test
    fun toSteps_alarmWithoutTime_asksUser() {
        val steps = IntentActionMapper.toSteps("set_alarm", "设个闹钟", context = null)
        assertNotNull(steps)
        assertEquals(true, steps!!.single().waitingForUser)
    }

    @Test
    fun toSteps_alarmWithTime() {
        val steps = IntentActionMapper.toSteps("set_alarm", "7:30叫我", context = null)
        assertNotNull(steps)
        assertEquals("set_alarm", steps!!.first().action)
        assertEquals("7:30", steps.first().targetText)
    }
}
