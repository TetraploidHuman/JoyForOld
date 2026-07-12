package com.tetraploid.joyforold.overlay

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.PageReadiness
import com.tetraploid.joyforold.agent.StructuredPageSnapshot

/**
 * Agent 视觉步骤（截图 / tap / type / send）期间临时隐藏悬浮层，避免污染截图与拦截点击。
 *
 * 隐藏后仅等待主线程 GONE（约 1–3ms），截图/点击结束立即恢复。
 */
object VisionOverlayGuard {
    private val visionUiActions = setOf("tap", "type", "send")

    fun actionNeedsHiddenOverlay(
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
    ): Boolean {
        if (!PageReadiness.needsVisionFallback(snapshot)) return false
        return action.action.lowercase() in visionUiActions
    }

    suspend fun <T> withHidden(block: suspend () -> T): T {
        AgentRuntime.pushVisionOverlaySuppressionAwait()
        return try {
            block()
        } finally {
            AgentRuntime.popVisionOverlaySuppression()
        }
    }
}
