package com.tetraploid.joyforold.overlay

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.PageReadiness
import com.tetraploid.joyforold.agent.PageScreenshotCapture
import com.tetraploid.joyforold.agent.StructuredPageSnapshot

/**
 * Agent 视觉步骤（截图 / tap / type / send）期间临时隐藏悬浮层，避免污染截图与拦截点击。
 *
 * 截图路径会清缓存并等一帧合成；点击路径 GONE 后等手势完成再恢复。
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
        val suppressor = VisionOverlaySuppressors.current
        if (suppressor.isVisionAgentActive()) {
            return block()
        }
        suppressor.pushSuppressionAwait(waitFrame = false)
        return try {
            block()
        } finally {
            suppressor.popSuppression()
        }
    }

    suspend fun <T> withHiddenForCapture(block: suspend () -> T): T {
        PageScreenshotCapture.invalidateCache()
        val suppressor = VisionOverlaySuppressors.current
        if (suppressor.isVisionAgentActive()) {
            return block()
        }
        suppressor.pushSuppressionAwait(waitFrame = true)
        return try {
            block()
        } finally {
            suppressor.popSuppression()
        }
    }
}
