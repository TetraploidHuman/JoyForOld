package com.tetraploid.joyforold.agent

import android.util.Log
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.privacy.SafeLog

/**
 * 将每步页面快览 + 完整无障碍结构树写入 logcat，便于诊断第三方 App a11y 质量。
 * 过滤：adb logcat -s JoyForOld/A11y:I JoyForOld:I
 */
object AgentPageDebugLog {
    private const val TAG = "JoyForOld/A11y"
    private const val CHUNK_CHARS = AgentContextLimits.DEBUG_LOG_CHUNK_CHARS

    fun logObservation(
        stepNo: Int,
        phase: String,
        service: JoyAccessibilityService,
        snapshot: StructuredPageSnapshot?,
        pageDiff: String = "",
        treeSnippet: String? = null,
        visionMode: Boolean = false,
        a11yUnavailable: Boolean = false,
        screenshotChars: Int = 0,
    ) {
        val pkg = snapshot?.packageName.orEmpty().ifBlank { "unknown" }
        val visionSuffix = buildString {
            if (visionMode || a11yUnavailable) {
                append(" 视觉模式=是")
                if (screenshotChars > 0) append(" screenshot=${screenshotChars}chars")
            }
            if (a11yUnavailable) append(" a11y=不可用")
        }
        SafeLog.i(
            "── 步骤$stepNo [$phase] package=$pkg$visionSuffix ──",
        )
        when {
            snapshot == null ->
                SafeLog.i("步骤$stepNo [$phase] 无页面快览（快照为空）")
            a11yUnavailable ->
                emitChunks(
                    "快览",
                    VisionPageContext.formatPageContext(
                        snapshot,
                        hasScreenshot = screenshotChars > 0,
                    ),
                )
            else ->
                emitChunks("快览", snapshot.toCompactSummary())
        }

        if (a11yUnavailable) {
            emitChunks("结构树", "（无障碍树不可用，已省略；请查看截图）")
        } else {
            val tree = treeSnippet?.takeIf { it.isNotBlank() }
                ?: service.snapshotTreeForDebug()
            emitChunks("结构树", tree)
        }

        if (pageDiff.isNotBlank()) {
            emitChunks("页面变化", pageDiff)
        }
    }

    private fun emitChunks(label: String, text: String) {
        if (text.isBlank()) return
        if (text.length <= CHUNK_CHARS) {
            Log.i(TAG, SafeLog.redact("$label:\n$text"))
            return
        }
        val total = (text.length + CHUNK_CHARS - 1) / CHUNK_CHARS
        var offset = 0
        var part = 1
        while (offset < text.length) {
            val end = minOf(offset + CHUNK_CHARS, text.length)
            Log.i(
                TAG,
                SafeLog.redact("$label($part/$total):\n${text.substring(offset, end)}"),
            )
            offset = end
            part++
        }
    }
}
