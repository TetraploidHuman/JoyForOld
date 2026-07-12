package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.playbooks.ImSendMessagePlaybook

/**
 * 固定多步 action 剧本注册表。
 *
 * **不由正则自动触发**；仅当 LLM 在规划中显式输出 `run_playbook` 时，由 [fromRunPlaybookAction] 解析并展开步骤。
 * 每类确定性 UI 流程对应 `playbooks/` 下的一个实现。
 */
object AgentActionPlaybook {

    const val ACTION_RUN_PLAYBOOK = "run_playbook"
    const val ID_SEND_IM_MESSAGE = "send_im_message"

    sealed interface Match {
        data class SendImMessage(val intent: ImSendMessagePlaybook.Intent) : Match
    }

    fun descriptionsForPrompt(): String = """
        - run_playbook: 一次展开**固定多步剧本**（本地按序执行，本步之后不再逐步问模型）。
          **仅当**用户意图明确、参数齐全、且剧本比逐步 tap/click 更合适时调用；否则用 open_app/click/type 逐步规划。
          · target_text: 剧本 ID（当前：$ID_SEND_IM_MESSAGE）
          · send_im_message 时：input_text=联系人名，message=消息正文（必填）；默认打开微信
          示例：{"action":"run_playbook","target_text":"$ID_SEND_IM_MESSAGE","input_text":"大女儿","message":"今晚回家吃饭"}
    """.trimIndent()

    fun isRunPlaybookAction(action: AgentAction): Boolean =
        action.action.equals(ACTION_RUN_PLAYBOOK, ignoreCase = true)

    /** 将 LLM 输出的 run_playbook 解析为已注册剧本；参数不全时返回 null。 */
    fun fromRunPlaybookAction(action: AgentAction): Match? {
        if (!isRunPlaybookAction(action)) return null
        return when (action.targetText?.trim()?.lowercase()) {
            ID_SEND_IM_MESSAGE -> {
                val contact = action.inputText?.trim().orEmpty()
                val message = action.message?.trim().orEmpty()
                if (contact.isBlank() || message.isBlank()) return null
                Match.SendImMessage(
                    ImSendMessagePlaybook.Intent(
                        contact = contact,
                        message = message,
                    ),
                )
            }
            else -> null
        }
    }

    fun allSteps(playbook: Match): List<AgentAction> = when (playbook) {
        is Match.SendImMessage -> ImSendMessagePlaybook.allSteps(playbook.intent)
    }

    /**
     * 展开 LLM 计划：run_playbook → 剧本步骤；返回可能激活的剧本（供分阶段 drain）。
     */
    fun expandPlannedActions(actions: List<AgentAction>): ExpandResult {
        var activePlaybook: Match? = null
        val expanded = mutableListOf<AgentAction>()
        for (action in actions) {
            if (isRunPlaybookAction(action)) {
                val match = fromRunPlaybookAction(action)
                if (match == null) {
                    expanded += action
                    continue
                }
                activePlaybook = match
            } else {
                expanded += action
            }
        }
        return ExpandResult(
            steps = expanded,
            activePlaybook = activePlaybook,
        )
    }

    data class ExpandResult(
        val steps: List<AgentAction>,
        val activePlaybook: Match?,
    )

    fun drainNextSteps(
        playbook: Match,
        stepRecords: List<AgentStepRecord>,
    ): List<AgentAction>? = when (playbook) {
        is Match.SendImMessage -> ImSendMessagePlaybook.drainNextSteps(playbook.intent, stepRecords)
    }

    fun isPlaybookCompleted(playbook: Match, stepRecords: List<AgentStepRecord>): Boolean =
        drainNextSteps(playbook, stepRecords) == null &&
            stepRecords.any { it.action.action.equals("finish", ignoreCase = true) && it.result.success }

    /** 已在聊天页时仅注入输入+发送（非 run_playbook，供本地结构指令等）。 */
    fun stepsOnOpenChatPage(message: String, appName: String = ImSendMessagePlaybook.DEFAULT_IM_APP): List<AgentAction> =
        ImSendMessagePlaybook.stepsOnChatPage(message, appName)
}
