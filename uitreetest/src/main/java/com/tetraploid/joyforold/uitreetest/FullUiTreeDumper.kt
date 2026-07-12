package com.tetraploid.joyforold.uitreetest

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 完整输出无障碍 UI 树，不做节点过滤或数量截断（仅受深度保护）。
 */
object FullUiTreeDumper {
    private const val MAX_DEPTH = 120
    private const val LOG_TAG = "UiTreeTest"

    fun dumpAllWindows(windows: List<AccessibilityWindowInfo>): String {
        if (windows.isEmpty()) return "(无窗口)"
        return buildString {
            appendLine("=== UITreeTest 完整无障碍树 ===")
            appendLine("窗口数: ${windows.size}")
            appendLine()
            windows.forEachIndexed { index, window ->
                appendLine("--- 窗口 ${index + 1} ---")
                appendLine("type=${windowTypeLabel(window.type)} active=${window.isActive} focused=${window.isFocused}")
                val root = window.root
                if (root == null) {
                    appendLine("(无 root)")
                } else {
                    val rootCopy = AccessibilityNodeInfo.obtain(root)
                    try {
                        appendLine("package=${rootCopy.packageName}")
                        appendNode(this, rootCopy, depth = 0, indexPath = "0")
                    } finally {
                        rootCopy.recycle()
                    }
                }
                if (index < windows.lastIndex) appendLine()
            }
        }
    }

    fun logToLogcat(text: String, chunkSize: Int = 3_500) {
        if (text.length <= chunkSize) {
            android.util.Log.i(LOG_TAG, text)
            return
        }
        var offset = 0
        var part = 1
        while (offset < text.length) {
            val end = (offset + chunkSize).coerceAtMost(text.length)
            android.util.Log.i(LOG_TAG, "[$part] ${text.substring(offset, end)}")
            offset = end
            part++
        }
    }

    private fun appendNode(
        builder: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        indexPath: String,
    ) {
        if (depth > MAX_DEPTH) {
            builder.append("  ".repeat(depth.coerceAtMost(20)))
                .append("... (超过最大深度 $MAX_DEPTH)")
                .appendLine()
            return
        }

        val indent = "  ".repeat(depth.coerceAtMost(30))
        val className = node.className?.toString().orEmpty()
        val shortClass = className.substringAfterLast('.')
        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val flags = buildList {
            if (node.isClickable) add("clickable")
            if (node.isLongClickable) add("longClickable")
            if (node.isEditable) add("editable")
            if (node.isScrollable) add("scrollable")
            if (node.isFocusable) add("focusable")
            if (node.isFocused) add("focused")
            if (node.isSelected) add("selected")
            if (node.isChecked) add("checked")
            if (node.isCheckable) add("checkable")
            if (node.isEnabled) add("enabled") else add("disabled")
            if (node.isVisibleToUser) add("visible") else add("invisible")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (node.isImportantForAccessibility) add("important")
            }
        }.joinToString(",")

        builder.append(indent)
            .append("- [")
            .append(indexPath)
            .append("] ")
            .append(shortClass.ifBlank { "Node" })
            .append(" bounds=[")
            .append(rect.left).append(',').append(rect.top).append('-')
            .append(rect.right).append(',').append(rect.bottom)
            .append(']')
            .append(" children=").append(node.childCount)
        if (viewId.isNotBlank()) builder.append(" id=\"").append(viewId).append('"')
        if (text.isNotBlank()) builder.append(" text=\"").append(text).append('"')
        if (desc.isNotBlank()) builder.append(" desc=\"").append(desc).append('"')
        if (hint.isNotBlank()) builder.append(" hint=\"").append(hint).append('"')
        if (flags.isNotBlank()) builder.append(" (").append(flags).append(')')
        builder.appendLine()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            appendNode(builder, child, depth + 1, "$indexPath.$i")
            child.recycle()
        }
    }

    private fun windowTypeLabel(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
        else -> "UNKNOWN($type)"
    }
}
