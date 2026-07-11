package com.tetraploid.joyforold.agent

object AgentActionWhitelist {
    private val allowed = AgentToolRegistry.toolNames.map { it.lowercase() }.toSet()

    fun isAllowed(action: String): Boolean = action.lowercase() in allowed

    fun blockReason(action: String): String? {
        if (isAllowed(action)) return null
        return "未知操作「$action」不在白名单内。只允许：${AgentToolRegistry.toolNames.joinToString()}。"
    }
}
