package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentStepRecord

/**
 * 根据 stepRecords 在 phase 图上定位进度。
 *
 * - [ActionSetDrain.RunActions]：普通 UI 动作批
 * - [ActionSetDrain.CapturePageTexts] / [ActionSetDrain.AskLlm]：需 Orchestrator 本地处理后写伪步骤
 * - [ActionSetDrain.Done]：完成（含成功 finish）
 */
object ActionSetFlowEngine {

    fun drainNextSteps(
        definition: ActionSetDefinition,
        params: ActionSetParams,
        stepRecords: List<AgentStepRecord>,
    ): ActionSetDrain {
        if (hasSuccessfulFinish(stepRecords)) return ActionSetDrain.Done

        var phaseId = definition.startPhaseId
        var recordCursor = 0

        while (true) {
            val phase = definition.phases[phaseId] ?: return ActionSetDrain.Done

            when (val kind = phase.kind) {
                is PhaseKind.CapturePageTexts -> {
                    val marker = AgentAction(
                        action = ACTION_CAPTURE_PAGE_TEXTS,
                        targetText = kind.intoParam,
                    )
                    val hit = findMatchingFrom(stepRecords, recordCursor, marker)
                    if (hit == null) {
                        return ActionSetDrain.CapturePageTexts(
                            phaseId = phase.id,
                            intoParam = kind.intoParam,
                        )
                    }
                    if (!hit.record.result.success) {
                        return ActionSetDrain.CapturePageTexts(
                            phaseId = phase.id,
                            intoParam = kind.intoParam,
                        )
                    }
                    recordCursor = hit.nextCursor
                    phaseId = phase.next ?: return ActionSetDrain.Done
                }

                is PhaseKind.AskLlm -> {
                    val marker = AgentAction(action = ACTION_ASK_LLM, targetText = phase.id)
                    val hit = findMatchingFrom(stepRecords, recordCursor, marker)
                    if (hit == null || !hit.record.result.success) {
                        return ActionSetDrain.AskLlm(
                            phaseId = phase.id,
                            systemPrompt = kind.systemPrompt(params),
                            userPrompt = kind.userPrompt(params),
                            writeFields = kind.writeFields,
                        )
                    }
                    recordCursor = hit.nextCursor
                    phaseId = phase.next ?: return ActionSetDrain.Done
                }

                is PhaseKind.Actions -> {
                    val planned = kind.actions.map { it(params) }
                    if (planned.isEmpty()) {
                        phaseId = phase.next ?: return ActionSetDrain.Done
                        continue
                    }

                    val match = matchPhase(
                        planned = planned,
                        branchIndex = kind.branchIndex,
                        hasBranch = kind.onSuccess != null || kind.onFail != null,
                        records = stepRecords,
                        startCursor = recordCursor,
                    )

                    when (match) {
                        is PhaseMatch.NeedActions -> return ActionSetDrain.RunActions(match.remaining)
                        is PhaseMatch.Branched -> {
                            recordCursor = match.nextCursor
                            phaseId = when {
                                match.success -> kind.onSuccess ?: phase.next
                                    ?: return ActionSetDrain.Done
                                else -> kind.onFail
                                    ?: phase.next
                                    ?: return ActionSetDrain.Done
                            }
                        }
                        is PhaseMatch.Completed -> {
                            recordCursor = match.nextCursor
                            if (planned.any { it.action.equals("finish", ignoreCase = true) }) {
                                return ActionSetDrain.Done
                            }
                            phaseId = phase.next ?: return ActionSetDrain.Done
                        }
                    }
                }
            }
        }
    }

    /**
     * 静态全量（规划/测试）：沿 onSuccess / next，跳过 capture / askLlm。
     */
    fun allStepsHappyPath(
        definition: ActionSetDefinition,
        params: ActionSetParams,
    ): List<AgentAction> {
        val out = mutableListOf<AgentAction>()
        val visited = mutableSetOf<String>()
        var phaseId: String? = definition.startPhaseId
        while (phaseId != null && phaseId !in visited) {
            visited += phaseId
            val phase = definition.phases[phaseId] ?: break
            when (val kind = phase.kind) {
                is PhaseKind.Actions -> {
                    out += kind.actions.map { it(params) }
                    phaseId = kind.onSuccess ?: phase.next
                }
                is PhaseKind.CapturePageTexts, is PhaseKind.AskLlm -> {
                    phaseId = phase.next
                }
            }
        }
        return out
    }

    fun isCompleted(
        definition: ActionSetDefinition,
        params: ActionSetParams,
        stepRecords: List<AgentStepRecord>,
    ): Boolean = hasSuccessfulFinish(stepRecords) &&
        drainNextSteps(definition, params, stepRecords) is ActionSetDrain.Done

    private fun hasSuccessfulFinish(stepRecords: List<AgentStepRecord>): Boolean =
        stepRecords.any {
            it.action.action.equals("finish", ignoreCase = true) && it.result.success
        }

    private data class RecordHit(
        val record: AgentStepRecord,
        val nextCursor: Int,
    )

    private fun findMatchingFrom(
        records: List<AgentStepRecord>,
        startCursor: Int,
        marker: AgentAction,
    ): RecordHit? {
        var i = startCursor
        while (i < records.size) {
            val rec = records[i]
            if (isWait(rec.action)) {
                i++
                continue
            }
            return if (actionsMatch(marker, rec.action)) {
                RecordHit(rec, i + 1)
            } else {
                null
            }
        }
        return null
    }

    private sealed class PhaseMatch {
        data class NeedActions(val remaining: List<AgentAction>) : PhaseMatch()
        data class Branched(
            val success: Boolean,
            val nextCursor: Int,
            val branchConsumed: Int,
        ) : PhaseMatch()
        data class Completed(val nextCursor: Int) : PhaseMatch()
    }

    private fun matchPhase(
        planned: List<AgentAction>,
        branchIndex: Int,
        hasBranch: Boolean,
        records: List<AgentStepRecord>,
        startCursor: Int,
    ): PhaseMatch {
        var cursor = startCursor
        var plannedIndex = 0

        while (plannedIndex < planned.size) {
            val expected = planned[plannedIndex]

            if (isWait(expected)) {
                if (cursor < records.size && actionsMatch(expected, records[cursor].action)) {
                    cursor++
                }
                plannedIndex++
                continue
            }

            if (cursor >= records.size) {
                return PhaseMatch.NeedActions(planned.drop(plannedIndex))
            }

            val record = records[cursor]
            if (!actionsMatch(expected, record.action)) {
                return PhaseMatch.NeedActions(planned.drop(plannedIndex))
            }

            cursor++
            val isBranchPoint = hasBranch && plannedIndex == branchIndex
            if (isBranchPoint) {
                return PhaseMatch.Branched(
                    success = record.result.success,
                    nextCursor = cursor,
                    branchConsumed = plannedIndex + 1,
                )
            }

            if (!record.result.success) {
                // 已消费失败记录，禁止把同一步再次 NeedActions（否则 click（续）死循环）
                return PhaseMatch.Branched(
                    success = false,
                    nextCursor = cursor,
                    branchConsumed = plannedIndex + 1,
                )
            }

            plannedIndex++
        }

        return PhaseMatch.Completed(nextCursor = cursor)
    }

    private fun isWait(action: AgentAction): Boolean =
        action.action.equals("wait", ignoreCase = true)

    internal fun actionsMatch(planned: AgentAction, actual: AgentAction): Boolean {
        if (!planned.action.equals(actual.action, ignoreCase = true)) return false
        return when (planned.action.lowercase()) {
            "open_app" -> {
                val want = planned.targetText?.trim().orEmpty()
                if (want.isEmpty()) true
                else actual.targetText?.contains(want, ignoreCase = true) == true
            }
            "click", "find_on_page", ACTION_CAPTURE_PAGE_TEXTS, ACTION_ASK_LLM -> {
                val want = planned.targetText?.trim().orEmpty()
                if (want.isEmpty()) true
                else actual.targetText?.trim().equals(want, ignoreCase = true) == true
            }
            "type" -> {
                val wantInput = planned.inputText?.trim().orEmpty()
                val inputOk = wantInput.isEmpty() ||
                    actual.inputText?.trim().equals(wantInput, ignoreCase = true) == true
                val wantTarget = planned.targetText?.trim().orEmpty()
                val targetOk = wantTarget.isEmpty() ||
                    actual.targetText?.contains(wantTarget, ignoreCase = true) == true
                inputOk && targetOk
            }
            "finish", "send", "wait" -> true
            else -> {
                val t = planned.targetText?.trim()
                t.isNullOrEmpty() || actual.targetText?.trim().equals(t, ignoreCase = true) == true
            }
        }
    }
}
