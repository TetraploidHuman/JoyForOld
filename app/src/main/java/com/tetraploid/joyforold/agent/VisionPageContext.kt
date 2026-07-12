package com.tetraploid.joyforold.agent

/**
 * 无障碍树不可用时的 LLM 页面上下文：只传 package/应用经验/截图提示，不传空的可点击/结构树列表。
 */
object VisionPageContext {
    fun formatPageContext(
        snapshot: StructuredPageSnapshot,
        hasScreenshot: Boolean,
    ): String = buildString {
        appendLine("=== 视觉观察 ===")
        if (snapshot.appHint.isNotBlank()) {
            appendLine(snapshot.appHint)
        }
        if (snapshot.packageName.isNotBlank()) {
            appendLine("package: ${snapshot.packageName}")
        }
        append(
            if (hasScreenshot) {
                "已附带屏幕截图，请根据截图识别界面元素并规划 tap/type。"
            } else {
                "截屏暂不可用，请先 wait 后重试；规划时使用 tap 归一化坐标。"
            },
        )
    }

    fun formatPageDiff(
        packageName: String,
        previousSnapshot: StructuredPageSnapshot?,
        previousVisionFingerprint: String?,
        currentVisionFingerprint: String?,
    ): String {
        val base = buildString {
            appendLine("=== 页面变化（视觉） ===")
            val prevPkg = previousSnapshot?.packageName.orEmpty()
            when {
                previousSnapshot == null ->
                    appendLine("首次观察：package=${packageName.ifBlank { "未知" }}")
                prevPkg.isNotBlank() && prevPkg != packageName ->
                    appendLine("应用切换：$prevPkg → $packageName")
                else ->
                    appendLine("应用未变：${packageName.ifBlank { "未知" }}")
            }
        }.trimEnd()
        return VisionScreenChange.augmentPageDiff(
            base,
            previousVisionFingerprint,
            currentVisionFingerprint,
        )
    }
}
