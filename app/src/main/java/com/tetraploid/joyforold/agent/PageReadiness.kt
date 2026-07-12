package com.tetraploid.joyforold.agent

/**
 * 判断无障碍快照是否已有足够信号可供 Agent 操作。
 */
object PageReadiness {
    fun isReadable(snapshot: StructuredPageSnapshot?, expectedPackage: String? = null): Boolean {
        if (snapshot == null) return false
        if (snapshot.packageName.isBlank()) return false
        if (ExternalWindowFilter.isSystemChromeSnapshot(snapshot)) return false
        if (!expectedPackage.isNullOrBlank() && snapshot.packageName != expectedPackage) return false
        if (snapshot.clickables.isNotEmpty()) return true
        if (snapshot.editables.isNotEmpty()) return true
        if (snapshot.sendButtons.isNotEmpty()) return true
        return snapshot.visibleTexts.size >= 2
    }

    private val emptyTreeNodePattern = Regex("""节选,\s*0\s*节点""")

    fun isEmptyTreeSnippet(detail: String): Boolean =
        detail.contains("(无结构节点)") || emptyTreeNodePattern.containsMatchIn(detail)

    fun needsVisionFallback(snapshot: StructuredPageSnapshot?): Boolean {
        if (snapshot == null) return true
        if (ExternalWindowFilter.isSystemChromeSnapshot(snapshot)) return true
        return !isReadable(snapshot)
    }

    fun isWrongChromeTree(detail: String): Boolean =
        ExternalWindowFilter.isSystemChromeTreeSnippet(detail)
}
