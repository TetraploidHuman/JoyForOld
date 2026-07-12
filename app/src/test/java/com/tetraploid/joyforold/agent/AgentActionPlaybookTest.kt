package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.playbooks.ImSendMessagePlaybook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionPlaybookTest {
    @Test
    fun fromRunPlaybookAction_parsesSendImMessage() {
        val action = AgentAction(
            action = AgentActionPlaybook.ACTION_RUN_PLAYBOOK,
            targetText = AgentActionPlaybook.ID_SEND_IM_MESSAGE,
            inputText = "响",
            message = "到家了",
        )
        val playbook = AgentActionPlaybook.fromRunPlaybookAction(action)
        assertTrue(playbook is AgentActionPlaybook.Match.SendImMessage)
        val intent = (playbook as AgentActionPlaybook.Match.SendImMessage).intent
        assertEquals("响", intent.contact)
        assertEquals("到家了", intent.message)
        assertEquals(ImSendMessagePlaybook.DEFAULT_IM_APP, intent.appName)
    }

    @Test
    fun fromRunPlaybookAction_nullWhenParamsMissing() {
        val action = AgentAction(
            action = AgentActionPlaybook.ACTION_RUN_PLAYBOOK,
            targetText = AgentActionPlaybook.ID_SEND_IM_MESSAGE,
            inputText = "响",
        )
        assertNull(AgentActionPlaybook.fromRunPlaybookAction(action))
    }

    @Test
    fun expandPlannedActions_activatesPlaybookWithoutInliningAllSteps() {
        val runPlaybook = AgentAction(
            action = AgentActionPlaybook.ACTION_RUN_PLAYBOOK,
            targetText = AgentActionPlaybook.ID_SEND_IM_MESSAGE,
            inputText = "大女儿",
            message = "今晚回家吃饭",
        )
        val expanded = AgentActionPlaybook.expandPlannedActions(listOf(runPlaybook))
        assertNotNull(expanded.activePlaybook)
        assertTrue(expanded.steps.isEmpty())
    }

    @Test
    fun allSteps_includesNavigationAndChatPage() {
        val playbook = AgentActionPlaybook.Match.SendImMessage(
            ImSendMessagePlaybook.Intent("响", "到家了"),
        )
        val steps = AgentActionPlaybook.allSteps(playbook)
        assertEquals("open_app", steps.first().action)
        assertTrue(steps.any { it.action == "type" && it.targetText == "380,895" })
        assertTrue(steps.any { it.action == "send" })
        assertEquals("finish", steps.last().action)
    }

    @Test
    fun drainNextSteps_returnsNavigationFirst() {
        val playbook = AgentActionPlaybook.Match.SendImMessage(
            ImSendMessagePlaybook.Intent("响", "到家了"),
        )
        val planned = AgentActionPlaybook.drainNextSteps(
            playbook = playbook,
            stepRecords = emptyList(),
        )
        assertNotNull(planned)
        assertEquals("open_app", planned!!.first().action)
    }

    @Test
    fun drainNextSteps_returnsChatAfterNavigation() {
        val playbook = AgentActionPlaybook.Match.SendImMessage(
            ImSendMessagePlaybook.Intent("响", "到家了"),
        )
        val records = listOf(
            stepRecord("open_app", "微信", success = true),
            stepRecord("type", input = "响", success = true),
            stepRecord("tap", "500,220", success = true),
        )
        val planned = AgentActionPlaybook.drainNextSteps(
            playbook = playbook,
            stepRecords = records,
        )
        assertNotNull(planned)
        assertTrue(planned!!.any { it.action == "send" })
    }

    @Test
    fun drainNextSteps_nullWhenPlaybookCompleted() {
        val playbook = AgentActionPlaybook.Match.SendImMessage(
            ImSendMessagePlaybook.Intent("响", "到家了"),
        )
        val records = listOf(
            stepRecord(
                "finish",
                message = "已尝试通过微信 发送：到家了",
                success = true,
            ),
        )
        assertNull(
            AgentActionPlaybook.drainNextSteps(
                playbook = playbook,
                stepRecords = records,
            ),
        )
    }

    private fun stepRecord(
        action: String,
        target: String? = null,
        input: String? = null,
        message: String? = null,
        success: Boolean,
    ) = AgentStepRecord(
        step = 1,
        action = AgentAction(action = action, targetText = target, inputText = input, message = message),
        result = ActionExecutionResult(success = success, summary = "ok"),
        pageDiff = "",
    )
}
