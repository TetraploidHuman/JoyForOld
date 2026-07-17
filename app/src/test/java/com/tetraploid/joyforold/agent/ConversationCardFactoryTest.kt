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
    fun overlayInteraction_hiddenInVisionMode() {
        val waiting = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "请确认",
            visionAgentActive = true,
        )
        assertNull(ConversationCardFactory.overlayInteraction(waiting))
    }

    @Test
    fun overlayInteraction_prefersSessionDisambiguationCard() {
        val disambiguation = ConversationCardFactory.disambiguation(
            prompt = "您想做什么？",
            options = listOf(
                DisambiguationOption(intentId = "send_msg", label = "发消息", confidence = 0.9f),
                DisambiguationOption(intentId = "call", label = "打电话", confidence = 0.8f),
            ),
        )
        val waiting = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "您想做什么？",
        )
        val card = ConversationCardFactory.overlayInteraction(waiting, listOf(disambiguation))
        assertNotNull(card)
        assertEquals(ConversationCardKind.Disambiguation, card!!.kind)
        assertEquals(2, card.bullets.size)
    }

    @Test
    fun overlaySessionCards_includePlanProgress_inA11yMode() {
        val plan = ConversationCardFactory.plan(
            listOf(TaskPhaseItem(1, "打开设置", TaskStepStatus.InProgress)),
        )!!
        val progress = ConversationCardFactory.progress("执行：click WLAN")
        val state = AgentUiState(isRunning = true, visionAgentActive = false)
        val cards = ConversationCardFactory.overlaySessionCards(state, listOf(plan, progress))
        assertEquals(2, cards.size)
        assertEquals(ConversationCardKind.Plan, cards[0].kind)
        assertEquals(ConversationCardKind.Progress, cards[1].kind)
    }

    @Test
    fun overlaySessionCards_emptyInVisionMode() {
        val plan = ConversationCardFactory.plan(
            listOf(TaskPhaseItem(1, "打开微信", TaskStepStatus.InProgress)),
        )!!
        val state = AgentUiState(isRunning = true, visionAgentActive = true)
        assertTrue(
            ConversationCardFactory.overlaySessionCards(state, listOf(plan)).isEmpty(),
        )
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
