package com.tetraploid.joyforold.agent

import java.util.UUID

enum class ConversationCardKind {
    User,
    Assistant,
    Plan,
    Progress,
    Info,
    Confirm,
    Disambiguation,
    Preview,
    Undo,
}

data class ConversationCard(
    val id: String = UUID.randomUUID().toString(),
    val kind: ConversationCardKind,
    val title: String,
    val body: String = "",
    val bullets: List<String> = emptyList(),
    val detailBullets: List<String> = emptyList(),
    val showBinaryActions: Boolean = false,
    /** Disambiguation: intent id per bullet label */
    val optionIds: List<String> = emptyList(),
)

object ConversationCardFactory {
    fun userCommand(text: String): ConversationCard = ConversationCard(
        kind = ConversationCardKind.User,
        title = "您的指令",
        body = text,
    )

    fun assistantMessage(text: String): ConversationCard = ConversationCard(
        kind = ConversationCardKind.Assistant,
        title = "助手",
        body = text,
    )

    fun plan(
        phases: List<TaskPhaseItem>,
        detailSteps: List<TaskStepItem> = emptyList(),
    ): ConversationCard? {
        if (phases.isEmpty()) return null
        return ConversationCard(
            kind = ConversationCardKind.Plan,
            title = "计划",
            bullets = phases.map { phase ->
                val prefix = when (phase.status) {
                    TaskStepStatus.Completed -> "✓ "
                    TaskStepStatus.InProgress -> "→ "
                    TaskStepStatus.Pending -> "· "
                }
                prefix + phase.label
            },
            detailBullets = detailSteps.map { step ->
                val prefix = when (step.status) {
                    TaskStepStatus.Completed -> "✓ "
                    TaskStepStatus.InProgress -> "→ "
                    TaskStepStatus.Pending -> "· "
                }
                prefix + step.label
            },
        )
    }

    fun progress(message: String): ConversationCard {
        return ConversationCard(
            kind = ConversationCardKind.Progress,
            title = "执行中",
            body = message.removePrefix("执行：").ifBlank { message },
        )
    }

    fun info(title: String, body: String): ConversationCard = ConversationCard(
        kind = ConversationCardKind.Info,
        title = title,
        body = body,
    )

    fun confirm(prompt: String, binary: Boolean): ConversationCard = ConversationCard(
        kind = ConversationCardKind.Confirm,
        title = if (binary) "请确认" else "等待您的回复",
        body = prompt,
        showBinaryActions = binary,
    )

    fun disambiguation(prompt: String, options: List<DisambiguationOption>): ConversationCard =
        ConversationCard(
            kind = ConversationCardKind.Disambiguation,
            title = "请选一下",
            body = prompt,
            bullets = options.map { it.label },
            optionIds = options.map { it.intentId },
        )

    fun preview(prompt: String): ConversationCard = ConversationCard(
        kind = ConversationCardKind.Preview,
        title = "执行前确认",
        body = prompt,
        showBinaryActions = true,
    )

    fun undo(message: String): ConversationCard = ConversationCard(
        kind = ConversationCardKind.Undo,
        title = "可撤销",
        body = message,
        showBinaryActions = true,
    )

    fun overlayInteraction(state: AgentUiState): ConversationCard? {
        if (!state.waitingForUserConfirm || state.confirmPrompt.isNullOrBlank()) return null
        return confirm(state.confirmPrompt, state.needsBinaryConfirm)
    }

    fun rebuildSessionCards(state: AgentUiState, userCommand: String? = null): List<ConversationCard> {
        val cards = mutableListOf<ConversationCard>()
        val command = userCommand?.trim().orEmpty()
        if (command.isNotBlank()) {
            cards += userCommand(command)
        }
        plan(state.taskPhases, state.taskSteps)?.let { cards += it }
        if (state.isRunning && state.statusMessage.isNotBlank()) {
            cards += progress(state.statusMessage)
        }
        if (state.waitingForUserConfirm && !state.confirmPrompt.isNullOrBlank()) {
            cards += confirm(state.confirmPrompt, state.needsBinaryConfirm)
        } else if (!state.isRunning && state.statusMessage.isNotBlank() &&
            state.taskPhases.isEmpty() && state.taskSteps.isEmpty()
        ) {
            cards += assistantMessage(state.statusMessage)
        }
        return cards
    }
}
