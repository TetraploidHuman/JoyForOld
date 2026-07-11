package com.tetraploid.joyforold.agent

import org.json.JSONObject

/**
 * 一次 API 规划 1~2 步，本地连续执行；单独占屏的动作必须单步规划。
 */
object AgentPlanParser {
    const val MAX_PLANNED_STEPS = 2

    private val SOLO_STEP_ACTIONS = setOf(
        "finish",
        "open_app",
        "list_apps",
        "wait",
        "dial_contact",
        "send_sms",
        "send",
        "read_tree",
        "navigate_home",
        "ask_family_for_help",
        "emergency_help",
        "open_weather",
        "open_camera",
        "open_gallery",
        "open_health_code",
        "open_payment_code",
        "open_font_settings",
        "read_unread_messages",
        "tell_time",
        "query_weather",
        "back",
        "home",
    )

    fun parsePlan(json: JSONObject): List<AgentAction> {
        val array = json.optJSONArray("actions")
        if (array != null && array.length() > 0) {
            val parsed = buildList {
                for (i in 0 until array.length()) {
                    add(AgentAction.fromJson(array.getJSONObject(i)))
                }
            }
            return sanitize(parsed)
        }
        return sanitize(listOf(AgentAction.fromJson(json)))
    }

    fun sanitize(actions: List<AgentAction>): List<AgentAction> {
        if (actions.isEmpty()) return actions
        val first = actions.first()
        if (isSoloStep(first)) return listOf(first)
        if (actions.size == 1) return actions
        val second = actions[1]
        if (isSoloStep(second)) return listOf(first)
        return actions.take(MAX_PLANNED_STEPS)
    }

    fun stopsBatchAfter(action: AgentAction): Boolean {
        return isSoloStep(action)
    }

    private fun isSoloStep(action: AgentAction): Boolean {
        val name = action.action.lowercase()
        if (name in SOLO_STEP_ACTIONS) return true
        if (action.finished || action.waitingForUser) return true
        return false
    }
}
