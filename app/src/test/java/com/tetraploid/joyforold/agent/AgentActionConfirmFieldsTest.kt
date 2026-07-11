package com.tetraploid.joyforold.agent

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentActionConfirmFieldsTest {
    @Test
    fun greetingFinish_withoutWaitingForUser_doesNotPause() {
        val action = AgentAction(
            action = "finish",
            message = "请问有什么可以帮你的？",
            finished = true,
            waitingForUser = false,
            needsBinaryConfirm = false,
        )
        assertFalse(action.waitingForUser)
        assertFalse(action.needsBinaryConfirm)
    }

    @Test
    fun sendConfirm_requiresBinaryConfirmFromAi() {
        val action = AgentAction(
            action = "finish",
            message = "即将发送消息，请确认。",
            waitingForUser = true,
            needsBinaryConfirm = true,
        )
        assertTrue(action.waitingForUser)
        assertTrue(action.needsBinaryConfirm)
    }

    @Test
    fun openQuestion_waitsWithoutBinaryConfirm() {
        val action = AgentAction(
            action = "finish",
            message = "请告诉我来电联系人。",
            waitingForUser = true,
            needsBinaryConfirm = false,
        )
        assertTrue(action.waitingForUser)
        assertFalse(action.needsBinaryConfirm)
    }

    @Test
    fun fromJson_parsesNeedsBinaryConfirm() {
        val action = AgentAction.fromJson(
            JSONObject(
                """
                {"action":"finish","waiting_for_user":true,"needs_binary_confirm":true}
                """.trimIndent(),
            ),
        )
        assertTrue(action.waitingForUser)
        assertTrue(action.needsBinaryConfirm)
    }
}
