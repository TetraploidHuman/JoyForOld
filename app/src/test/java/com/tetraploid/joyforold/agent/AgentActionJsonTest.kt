package com.tetraploid.joyforold.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentActionJsonTest {
    @Test
    fun fromJson_parsesActionFields() {
        val json = JSONObject(
            """
            {
              "action": "click",
              "target_text": "发送",
              "input_text": "",
              "message": "",
              "finished": false,
              "waiting_for_user": false
            }
            """.trimIndent(),
        )

        val action = AgentAction.fromJson(json)
        assertEquals("click", action.action)
        assertEquals("发送", action.targetText)
        assertFalse(action.finished)
        assertFalse(action.waitingForUser)
    }

    @Test
    fun fromJson_defaultsFinishWhenActionIsFinish() {
        val action = AgentAction.fromJson(JSONObject("""{"action":"finish","message":"完成"}"""))
        assertTrue(action.finished)
        assertEquals("完成", action.message)
    }

    @Test
    fun toJson_roundTrip() {
        val original = AgentAction(
            action = "type",
            inputText = "你好",
            waitingForUser = true,
            needsBinaryConfirm = true,
        )
        val restored = AgentAction.fromJson(original.toJson())
        assertEquals(original.action, restored.action)
        assertEquals(original.inputText, restored.inputText)
        assertEquals(original.waitingForUser, restored.waitingForUser)
        assertEquals(original.needsBinaryConfirm, restored.needsBinaryConfirm)
    }
}
