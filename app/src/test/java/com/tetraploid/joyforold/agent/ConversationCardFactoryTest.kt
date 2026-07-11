package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCardFactoryTest {
    @Test
    fun overlayInteraction_onlyWhenWaitingForConfirm() {
        val idle = AgentUiState()
        assertNull(ConversationCardFactory.overlayInteraction(idle))

        val waiting = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "有什么可以帮您？",
            needsBinaryConfirm = false,
        )
        val card = ConversationCardFactory.overlayInteraction(waiting)
        assertNotNull(card)
        assertEquals(ConversationCardKind.Confirm, card!!.kind)
        assertTrue(!card.showBinaryActions)
    }

    @Test
    fun planCard_listsPhaseLabels() {
        val phases = listOf(
            TaskPhaseItem(1, "打开微信", TaskStepStatus.Pending),
            TaskPhaseItem(2, "发送消息", TaskStepStatus.Pending),
        )
        val details = listOf(
            TaskStepItem(1, "click 发送", TaskStepStatus.Completed),
        )
        val card = ConversationCardFactory.plan(phases, details)
        assertNotNull(card)
        assertEquals(2, card!!.bullets.size)
        assertEquals(1, card.detailBullets.size)
    }
}
