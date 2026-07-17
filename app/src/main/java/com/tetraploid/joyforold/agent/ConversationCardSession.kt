package com.tetraploid.joyforold.agent

/**
 * 管理单次 Agent 会话中的对话卡片列表，与 UI 状态发布逻辑解耦。
 */
class ConversationCardSession {
    private val cards = mutableListOf<ConversationCard>()

    fun list(): List<ConversationCard> = cards.toList()

    fun reset(userCommand: String) {
        cards.clear()
        val trimmed = userCommand.trim()
        if (trimmed.isNotBlank()) {
            cards += ConversationCardFactory.userCommand(trimmed)
        }
    }

    fun upsert(card: ConversationCard) {
        val index = cards.indexOfFirst { it.kind == card.kind && it.id == card.id }
        if (index >= 0) {
            cards[index] = card
            return
        }
        val kindIndex = cards.indexOfFirst { it.kind == card.kind }
        if (kindIndex >= 0 && card.kind in REPLACEABLE_KINDS) {
            cards[kindIndex] = card
        } else {
            cards += card
        }
    }

    fun append(card: ConversationCard) {
        if (isDuplicate(card)) return
        cards += card
    }

    fun removeByKind(kind: ConversationCardKind) {
        cards.removeAll { it.kind == kind }
    }

    fun isDuplicate(card: ConversationCard): Boolean {
        val body = card.body.trim()
        if (body.isNotBlank() && cards.any { it.body.trim() == body }) return true
        if (card.kind == ConversationCardKind.Assistant && body.isNotBlank()) {
            if (cards.any {
                    (it.kind == ConversationCardKind.Confirm || it.kind == ConversationCardKind.User) &&
                        it.body.trim() == body
                }
            ) {
                return true
            }
        }
        return false
    }

    fun overlayInteractionCard(state: AgentUiState): ConversationCard? =
        ConversationCardFactory.overlayInteraction(state, cards)

    fun overlaySessionCards(state: AgentUiState): List<ConversationCard> =
        ConversationCardFactory.overlaySessionCards(state, cards)

    companion object {
        private val REPLACEABLE_KINDS = setOf(
            ConversationCardKind.Plan,
            ConversationCardKind.Progress,
            ConversationCardKind.Confirm,
            ConversationCardKind.Disambiguation,
            ConversationCardKind.Preview,
            ConversationCardKind.Undo,
        )
    }
}
