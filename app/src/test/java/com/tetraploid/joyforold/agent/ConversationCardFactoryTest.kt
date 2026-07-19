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
    fun overlayInteraction_showsConfirmEvenInVisionLatch() {
        val waiting = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "请确认发送",
            needsBinaryConfirm = true,
            visionAgentActive = true,
        )
        val card = ConversationCardFactory.overlayInteraction(waiting)
        assertNotNull(card)
        assertEquals(ConversationCardKind.Confirm, card!!.kind)
        assertTrue(card.showBinaryActions)
    }

    @Test
    fun overlayInteraction_undoHiddenInVisionMode() {
        val undo = ConversationCardFactory.undo("刚才的操作可以撤销，要撤销吗？")
        val state = AgentUiState(visionAgentActive = true)
        assertNull(ConversationCardFactory.overlayInteraction(state, listOf(undo)))
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
    fun overlaySessionCards_forcesConfirmWhileWaitingEvenIfVisionLatch() {
        val plan = ConversationCardFactory.plan(
            listOf(TaskPhaseItem(1, "打开微信", TaskStepStatus.Completed)),
        )!!
        val state = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "即将发送消息。请确认：要说「发送」还是「取消」？",
            needsBinaryConfirm = true,
            visionAgentActive = true,
        )
        val cards = ConversationCardFactory.overlaySessionCards(state, listOf(plan))
        assertEquals(1, cards.size)
        assertEquals(ConversationCardKind.Confirm, cards[0].kind)
        assertTrue(cards[0].showBinaryActions)
    }

    @Test
    fun overlaySessionCards_waitingShowsOnlyInteractionCard() {
        val plan = ConversationCardFactory.plan(
            listOf(TaskPhaseItem(1, "打开微信", TaskStepStatus.Completed)),
        )!!
        val confirm = ConversationCardFactory.confirm("请确认发送", binary = true)
        val state = AgentUiState(
            waitingForUserConfirm = true,
            confirmPrompt = "请确认发送",
            needsBinaryConfirm = true,
        )
        val cards = ConversationCardFactory.overlaySessionCards(state, listOf(plan, confirm))
        assertEquals(1, cards.size)
        assertEquals(ConversationCardKind.Confirm, cards[0].kind)
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
