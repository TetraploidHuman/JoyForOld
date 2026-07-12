package com.tetraploid.joyforold.agent

/**
 * 微信等 App 无障碍指纹恒定时，用截图指纹判断页面是否变化。
 */
object VisionScreenChange {
    const val CHANGED_MARKER = "【视觉】截图已变化"
    const val UNCHANGED_MARKER = "【视觉】截图未变"

    fun fingerprint(screenshotBase64: String?): String? {
        if (screenshotBase64.isNullOrBlank()) return null
        val sample = buildString {
            append(screenshotBase64.length)
            append(':')
            append(screenshotBase64.take(256))
            append(screenshotBase64.takeLast(256))
        }
        return sample.hashCode().toString()
    }

    fun augmentPageDiff(
        baseDiff: String,
        previousFingerprint: String?,
        currentFingerprint: String?,
    ): String {
        if (currentFingerprint.isNullOrBlank()) return baseDiff
        val changed = previousFingerprint != null && previousFingerprint != currentFingerprint
        return buildString {
            append(baseDiff.trimEnd())
            appendLine()
            if (changed) {
                appendLine("$CHANGED_MARKER（无障碍树不可用，以截图为准；上一步 tap 可能已生效）")
            } else if (previousFingerprint != null) {
                appendLine("$UNCHANGED_MARKER（上一步可能未推进界面）")
            } else {
                appendLine("【视觉】已附带截图（无障碍树不可用）")
            }
        }.trimEnd()
    }

    fun screenshotChanged(pageDiff: String): Boolean =
        pageDiff.contains(CHANGED_MARKER)

    fun indicatesNoProgress(pageDiff: String): Boolean {
        if (screenshotChanged(pageDiff)) return false
        if (pageDiff.contains(UNCHANGED_MARKER)) return true
        return AgentActionGuard.pageDiffIndicatesNoChangeA11yOnly(pageDiff)
    }
}
