package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction

typealias ActionFactory = (ActionSetParams) -> AgentAction

/** 伪动作名：本地采页面列表/可见文案切片写入 params（非整棵 UI 树）。 */
const val ACTION_CAPTURE_PAGE_TEXTS = "action_set_capture_page_texts"

/** 伪动作名：窄域 askLlm，仅基于候选切片选型后写回 params。 */
const val ACTION_ASK_LLM = "action_set_ask_llm"

sealed class PhaseKind {
    data class Actions(
        val actions: List<ActionFactory>,
        val branchIndex: Int = 0,
        val onSuccess: String? = null,
        val onFail: String? = null,
    ) : PhaseKind()

    data class CapturePageTexts(
        val intoParam: String,
    ) : PhaseKind()

    data class AskLlm(
        val writeFields: List<String>,
        val systemPrompt: (ActionSetParams) -> String,
        val userPrompt: (ActionSetParams) -> String,
    ) : PhaseKind()
}

data class PhaseDefinition(
    val id: String,
    val kind: PhaseKind,
    /** 相位完成后的下一相（Actions 分支优先生效时由 onSuccess/onFail 覆盖）。 */
    val next: String? = null,
)

data class ActionSetDefinition(
    val id: String,
    val paramSpecs: List<ParamSpec>,
    val phases: Map<String, PhaseDefinition>,
    val startPhaseId: String,
    val uiLabel: (ActionSetParams) -> String,
    val promptDescription: String = "",
) {
    fun resolveParams(action: AgentAction): ActionSetParams? = parseParams(paramSpecs, action)

    fun resolveActions(phaseId: String, params: ActionSetParams): List<AgentAction> {
        val kind = phases.getValue(phaseId).kind
        return when (kind) {
            is PhaseKind.Actions -> kind.actions.map { it(params) }
            is PhaseKind.CapturePageTexts -> listOf(
                AgentAction(action = ACTION_CAPTURE_PAGE_TEXTS, targetText = kind.intoParam),
            )
            is PhaseKind.AskLlm -> listOf(
                AgentAction(action = ACTION_ASK_LLM, targetText = phaseId),
            )
        }
    }
}

/** [ActionSetFlowEngine.drainNextSteps] 的返回值。 */
sealed class ActionSetDrain {
    data class RunActions(val steps: List<AgentAction>) : ActionSetDrain()

    data class CapturePageTexts(
        val phaseId: String,
        val intoParam: String,
    ) : ActionSetDrain()

    data class AskLlm(
        val phaseId: String,
        val systemPrompt: String,
        val userPrompt: String,
        val writeFields: List<String>,
    ) : ActionSetDrain()

    data object Done : ActionSetDrain()
}
