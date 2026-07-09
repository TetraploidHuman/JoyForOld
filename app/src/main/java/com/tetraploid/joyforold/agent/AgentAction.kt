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

        fun parsePlan(json: JSONObject): List<AgentAction> {
            val stepsArray = json.optJSONArray("steps")
            if (stepsArray != null && stepsArray.length() > 0) {
                val steps = buildList {
                    for (i in 0 until stepsArray.length()) {
                        add(fromJson(stepsArray.getJSONObject(i)))
                    }
                }
                if (steps.none { it.action.equals("finish", ignoreCase = true) || it.finished }) {
                    return steps + AgentAction(
                        action = "finish",
                        message = json.optString("message").ifBlank { "已完成" },
                        finished = true,
                    )
                }
                return steps
            }
            return listOf(fromJson(json))
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
)
