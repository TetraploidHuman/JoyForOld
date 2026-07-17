package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSetParamsTest {
    private val specs = listOf(
        ParamSpec("contact", required = true, source = ParamSource.INPUT_TEXT),
        ParamSpec("message", required = true, source = ParamSource.MESSAGE),
        ParamSpec("appName", required = false, defaultValue = "微信"),
        ParamSpec("candidates", required = false, defaultValue = ""),
    )

    @Test
    fun parseParams_readsRequiredFields() {
        val params = parseParams(
            specs,
            AgentAction(
                action = "run_action_set",
                inputText = "大女儿",
                message = "吃饭了",
            ),
        )
        assertEquals("大女儿", params!!["contact"])
        assertEquals("吃饭了", params["message"])
        assertEquals("微信", params["appName"])
    }

    @Test
    fun parseParams_nullWhenRequiredMissing() {
        assertNull(
            parseParams(
                specs,
                AgentAction(action = "run_action_set", inputText = "大女儿"),
            ),
        )
    }

    @Test
    fun parseParams_appliesOptionalDefault() {
        val params = parseParams(
            specs,
            AgentAction(
                action = "run_action_set",
                inputText = "响",
                message = "到家了",
            ),
        )
        assertTrue(params!!.values.containsKey("appName"))
        assertEquals("微信", params["appName"])
    }
}
