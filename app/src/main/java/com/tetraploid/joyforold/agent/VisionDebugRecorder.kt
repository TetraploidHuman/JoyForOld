package com.tetraploid.joyforold.agent

import android.util.Log
import java.io.File

/**
 * 保存发给 LLM 的截图，并在 AI 规划的 tap/send 坐标上画标记。
 * 目录：Android/data/.../files/agent_vision_debug/
 * logcat：adb logcat -s JoyForOld/VisionDebug:I
 */
object VisionDebugRecorder {
    private const val TAG = "JoyForOld/VisionDebug"

    fun recordLlmInput(
        store: VisionDebugStore?,
        stepNo: Int,
        phase: String,
        screenshotBase64: String?,
    ) {
        val enabledStore = store?.takeIf { it.isEnabled() } ?: return
        val base64 = screenshotBase64?.takeIf { it.isNotBlank() } ?: return
        val bitmap = VisionTapAnnotator.decodeBase64Jpeg(base64) ?: return
        val file = enabledStore.debugDir().resolve("s${stepNo.pad()}_llm_${safeToken(phase)}.jpg")
        if (VisionTapAnnotator.saveJpeg(bitmap, file)) {
            Log.i(TAG, "已保存 LLM 截图：${file.absolutePath} phase=$phase")
        }
        bitmap.recycle()
    }

    fun recordTapPlan(
        store: VisionDebugStore?,
        stepNo: Int,
        action: AgentAction,
        screenshotBase64: String?,
    ) {
        val enabledStore = store?.takeIf { it.isEnabled() } ?: return
        val base64 = screenshotBase64?.takeIf { it.isNotBlank() } ?: return
        val coords = VisionTapAnnotator.parseNormalizedCoords(action.targetText) ?: return
        val source = VisionTapAnnotator.decodeBase64Jpeg(base64) ?: return
        val (xNorm, yNorm) = coords
        val actionName = action.action.lowercase()
        val label = when (actionName) {
            "send" -> "send"
            else -> "tap"
        }
        val annotated = VisionTapAnnotator.annotateTap(
            source = source,
            xNorm = xNorm,
            yNorm = yNorm,
            label = "AI → $label",
        )
        val file = enabledStore.debugDir()
            .resolve("s${stepNo.pad()}_${label}_${xNorm}_${yNorm}.jpg")
        if (VisionTapAnnotator.saveJpeg(annotated, file)) {
            Log.i(
                TAG,
                "已保存坐标标记图：${file.absolutePath} action=${action.action} target=${action.targetText}",
            )
        }
        if (annotated !== source) annotated.recycle()
        source.recycle()
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private fun safeToken(raw: String): String =
        raw.replace(Regex("""[^\w\u4e00-\u9fff-]+"""), "_").trim('_').take(24)
            .ifBlank { "observe" }
}
