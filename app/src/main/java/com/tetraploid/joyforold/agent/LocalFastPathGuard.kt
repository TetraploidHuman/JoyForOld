package com.tetraploid.joyforold.agent

/**
 * 本地快路径：执行前预告 + 可撤销动作登记。
 */
object LocalFastPathGuard {
    private val previewSources = setOf("template", "offline_nlu", "local_system", "local", "preset")

    fun needsPreview(route: CommandRouteResolver.Route): Boolean {
        if (route.source !in previewSources) return false
        val cap = IntentCapabilityMatrix.capabilityForSteps(route.steps) ?: return false
        if (cap.riskTier != IntentCapabilityMatrix.RiskTier.LOW) return false
        if (cap.confirmPolicy != IntentCapabilityMatrix.ConfirmPolicy.NONE) return false
        return true
    }

    fun previewMessage(route: CommandRouteResolver.Route): String {
        val action = IntentCapabilityMatrix.primaryActionOf(route.steps).orEmpty()
        val finishMsg = route.steps.lastOrNull { it.action.equals("finish", ignoreCase = true) }
            ?.message
            ?.trim()
            .orEmpty()
        if (finishMsg.isNotBlank()) {
            return "即将${finishMsg.removePrefix("已").removePrefix("正在")}，确认执行吗？"
        }
        return "即将执行：${
            com.tetraploid.joyforold.offline.nlu.IntentActionMapper.describeAction(action, route.steps)
        }，确认吗？"
    }

    private val undoableActions = setOf("open_app", "navigate_home", "navigate_to")

    fun isUndoable(steps: List<AgentAction>): Boolean {
        val action = IntentCapabilityMatrix.primaryActionOf(steps).orEmpty()
        return action in undoableActions || action.startsWith("open_")
    }

    fun undoMessage(steps: List<AgentAction>): String {
        return if (isUndoable(steps)) "已撤销，返回桌面" else "已撤销"
    }
}

data class LocalUndoOffer(
    val action: String,
    val message: String,
    val expiresAtMs: Long,
)

object LocalUndoRegistry {
    @Volatile
    private var current: LocalUndoOffer? = null

    fun register(steps: List<AgentAction>) {
        if (!LocalFastPathGuard.isUndoable(steps)) {
            current = null
            return
        }
        val action = IntentCapabilityMatrix.primaryActionOf(steps).orEmpty()
        current = LocalUndoOffer(
            action = action,
            message = LocalFastPathGuard.undoMessage(steps),
            expiresAtMs = System.currentTimeMillis() + UNDO_WINDOW_MS,
        )
    }

    fun peek(): LocalUndoOffer? {
        val offer = current ?: return null
        if (System.currentTimeMillis() > offer.expiresAtMs) {
            current = null
            return null
        }
        return offer
    }

    fun consume(): LocalUndoOffer? {
        val offer = peek() ?: return null
        current = null
        return offer
    }

    fun clear() {
        current = null
    }

    private const val UNDO_WINDOW_MS = 12_000L
}
