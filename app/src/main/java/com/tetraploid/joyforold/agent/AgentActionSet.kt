package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.actionsets.ImSendMessageActionSet
import com.tetraploid.joyforold.agent.actionsets.MapNavigateActionSet
import com.tetraploid.joyforold.agent.actionsets.TaobaoSearchActionSet
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetDrain
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetFlowEngine
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetParams
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetRegistry

/**
 * 固定多步 action 动作组（ActionSet）入口。
 *
 * **目的**：把「开 App → 点搜索 → 输入」等固定路径留在本地执行，避免主规划轮
 * 把几万 token 的整棵 UI 树反复发给 LLM。流程中若需在**会变化的列表**里选型，
 * 用 `captureTexts` + 窄域 `askLlm`，只喂列表候选而不是全树。
 *
 * **不由正则自动触发**；仅当 LLM 输出 `run_action_set` 时解析并分阶段 drain。
 */
object AgentActionSet {

    const val ACTION_RUN_ACTION_SET = "run_action_set"
    const val ID_SEND_IM_MESSAGE = "send_im_message"
    const val ID_TAOBAO_SEARCH = "taobao_search"
    const val ID_TAOBAO_SEARCH_OPEN = "taobao_search_open"
    const val ID_MAP_NAVIGATE = MapNavigateActionSet.ID

    /** 触发加载已注册动作组（保证各 ActionSet object init 跑完）。 */
    @Suppress("unused")
    private val installed: String = listOf(
        ImSendMessageActionSet.definition.id,
        TaobaoSearchActionSet.searchOnly.id,
        TaobaoSearchActionSet.searchAndOpen.id,
        MapNavigateActionSet.definition.id,
    ).joinToString()

    class Match(
        val id: String,
        initialParams: ActionSetParams,
    ) {
        private val mutableParams = initialParams.values.toMutableMap()

        val params: ActionSetParams
            get() = ActionSetParams(mutableParams.toMap())

        fun updateParams(updates: Map<String, String>) {
            for ((k, v) in updates) {
                val trimmed = v.trim()
                if (trimmed.isNotEmpty()) mutableParams[k] = trimmed
            }
        }
    }

    fun descriptionsForPrompt(): String {
        installed
        return """
        - run_action_set: 一次展开**固定多步动作组**（本地执行固定 UI 路径，动作组进行中尽量不再调用主规划、不反复塞整棵 UI 树）。
          动作组内若需选型：本地采可点文案切片 → 窄域 askLlm 选型（非整棵树）。首次规划仍可能带页面摘要。
          **仅当**用户意图明确、参数齐全、且动作组比逐步 tap/click 更合适时调用；否则用 open_app/click/type 逐步规划。
          · target_text: 动作组 ID
          · $ID_SEND_IM_MESSAGE（微信发消息）：input_text=联系人名，message=消息正文（必填）
            示例：{"action":"run_action_set","target_text":"$ID_SEND_IM_MESSAGE","input_text":"大女儿","message":"今晚回家吃饭"}
          · $ID_TAOBAO_SEARCH（淘宝只搜索）：input_text=搜索关键词（必填）；打开淘宝、搜完停在结果页
            示例：{"action":"run_action_set","target_text":"$ID_TAOBAO_SEARCH","input_text":"一加手机"}
          · $ID_TAOBAO_SEARCH_OPEN（淘宝搜并打开商品）：input_text=搜索关键词（必填）；搜完后按结果列表窄域选型并进详情（不加购）
            示例：{"action":"run_action_set","target_text":"$ID_TAOBAO_SEARCH_OPEN","input_text":"一加手机"}
          · $ID_MAP_NAVIGATE（地图导航到某地）：input_text=目的地关键词（必填，如「肯德基」「公园」）；打开周边结果后选最近地点、点路线并开始导航
            示例：{"action":"run_action_set","target_text":"$ID_MAP_NAVIGATE","input_text":"肯德基"}
    """.trimIndent()
    }

    fun isRunActionSetAction(action: AgentAction): Boolean =
        action.action.equals(ACTION_RUN_ACTION_SET, ignoreCase = true)

    /** 任务进度 / 状态栏等 UI 展示用文案。 */
    fun uiLabel(action: AgentAction): String? {
        if (!isRunActionSetAction(action)) return null
        installed
        val id = action.targetText?.trim().orEmpty()
        if (id.isBlank()) return "执行动作组"
        val def = ActionSetRegistry.get(id) ?: return "动作组：$id"
        val params = def.resolveParams(action) ?: ActionSetParams(emptyMap())
        return def.uiLabel(params)
    }

    /** 将 LLM 输出的 run_action_set 解析为已注册动作组；参数不全时返回 null。 */
    fun fromRunActionSetAction(action: AgentAction): Match? {
        if (!isRunActionSetAction(action)) return null
        installed
        val id = action.targetText?.trim().orEmpty()
        if (id.isBlank()) return null
        val def = ActionSetRegistry.get(id) ?: return null
        val params = def.resolveParams(action) ?: return null
        return Match(id = def.id, initialParams = params)
    }

    fun allSteps(actionSet: Match): List<AgentAction> {
        installed
        val def = ActionSetRegistry.get(actionSet.id) ?: return emptyList()
        return ActionSetFlowEngine.allStepsHappyPath(def, actionSet.params)
    }

    /**
     * 展开 LLM 计划：run_action_set → 激活动作组（供分阶段 drain）；不把步骤内联进计划。
     */
    fun expandPlannedActions(actions: List<AgentAction>): ExpandResult {
        var activeActionSet: Match? = null
        val expanded = mutableListOf<AgentAction>()
        for (action in actions) {
            if (isRunActionSetAction(action)) {
                val match = fromRunActionSetAction(action)
                if (match == null) {
                    expanded += action
                    continue
                }
                activeActionSet = match
            } else {
                expanded += action
            }
        }
        return ExpandResult(
            steps = expanded,
            activeActionSet = activeActionSet,
        )
    }

    data class ExpandResult(
        val steps: List<AgentAction>,
        val activeActionSet: Match?,
    )

    fun drainNextSteps(
        actionSet: Match,
        stepRecords: List<AgentStepRecord>,
    ): ActionSetDrain {
        installed
        val def = ActionSetRegistry.get(actionSet.id) ?: return ActionSetDrain.Done
        return ActionSetFlowEngine.drainNextSteps(def, actionSet.params, stepRecords)
    }

    fun isActionSetCompleted(actionSet: Match, stepRecords: List<AgentStepRecord>): Boolean {
        installed
        val def = ActionSetRegistry.get(actionSet.id) ?: return false
        return ActionSetFlowEngine.isCompleted(def, actionSet.params, stepRecords)
    }

    /** 已在微信聊天页时仅注入输入+发送（非 run_action_set，供本地结构指令等）。 */
    fun stepsOnOpenChatPage(message: String): List<AgentAction> =
        ImSendMessageActionSet.stepsOnChatPage(message)
}
