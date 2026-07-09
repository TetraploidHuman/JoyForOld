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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("target_text", targetText.orEmpty())
        put("input_text", inputText.orEmpty())
        put("message", message.orEmpty())
        put("finished", finished)
        put("waiting_for_user", waitingForUser)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentAction {
            val action = json.optString("action", "finish")
            return AgentAction(
                action = action,
                targetText = json.optString("target_text").ifBlank { null },
                inputText = json.optString("input_text").ifBlank { null },
                message = json.optString("message").ifBlank { null },
                finished = json.optBoolean("finished", action.equals("finish", ignoreCase = true)),
                waitingForUser = json.optBoolean("waiting_for_user", false),
            )
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
    val sessionId: String? = null,
)
