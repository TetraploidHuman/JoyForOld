package com.tetraploid.joyforold.agent

import org.json.JSONObject

data class AgentAction(
    val action: String,
    val targetText: String? = null,
    val inputText: String? = null,
    val message: String? = null,
    val finished: Boolean = false,
    /** AI/计划显式标记：finish 后需等待用户回复再继续（由弹窗展示 message） */
    val waitingForUser: Boolean = false,
    /** AI/本地守卫显式标记：须二选一确认（发送/取消），否则为开放问答 */
    val needsBinaryConfirm: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("target_text", targetText.orEmpty())
        put("input_text", inputText.orEmpty())
        put("message", message.orEmpty())
        put("finished", finished)
        put("waiting_for_user", waitingForUser)
        put("needs_binary_confirm", needsBinaryConfirm)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentAction {
            val action = json.optString("action", "finish")
            return normalize(
                AgentAction(
                    action = action,
                    targetText = json.optString("target_text").ifBlank { null },
                    inputText = json.optString("input_text").ifBlank { null },
                    message = json.optString("message").ifBlank { null },
                    finished = json.optBoolean("finished", action.equals("finish", ignoreCase = true)),
                    waitingForUser = json.optBoolean("waiting_for_user", false),
                    needsBinaryConfirm = json.optBoolean("needs_binary_confirm", false),
                ),
            )
        }

        /** 模型偶发把 type 的文案写在 target_text，本地补齐 input_text。 */
        fun normalize(action: AgentAction): AgentAction {
            var normalized = action
            // finished 只对 finish 有终止语义；send/click 带 finished:true 时仍须先执行动作
            if (!normalized.action.equals("finish", ignoreCase = true) && normalized.finished) {
                normalized = normalized.copy(finished = false)
            }
            if (!normalized.action.equals("type", ignoreCase = true)) return normalized
            if (!normalized.inputText.isNullOrBlank()) return normalized
            val target = normalized.targetText?.trim().orEmpty()
            if (target.isBlank()) return normalized
            return normalized.copy(inputText = target, targetText = null)
        }
    }
}

data class AgentStepLog(
    val step: Int,
    val action: AgentAction,
    val success: Boolean,
    val detail: String,
)

data class AgentRunResult(
    val success: Boolean,
    val summary: String,
    val logs: List<AgentStepLog>,
    val waitingForUserConfirm: Boolean = false,
    val confirmPrompt: String? = null,
    val needsBinaryConfirm: Boolean = false,
    val sessionId: String? = null,
)
