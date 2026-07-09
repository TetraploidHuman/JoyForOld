package com.tetraploid.joyforold.agent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object UiTreeSerializer {
    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 50

    fun serialize(root: AccessibilityNodeInfo): String {
        val screenHeight = UiNodeHeuristics.screenHeight(root)
        val builder = StringBuilder()
        val counter = Counter()
        appendNode(builder, root, depth = 0, indexPath = "0", counter, screenHeight)
        val body = builder.toString()
        return buildString {
            appendLine("=== 结构树(节选, ${counter.value} 节点) ===")
            append(body.ifBlank { "(无结构节点)" })
            if (counter.truncated) {
                appendLine("... 结构树已截断，请优先参考页面快览中的可点击/可输入项")
            }
        }
    }

    private fun appendNode(
        builder: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        indexPath: String,
        counter: Counter,
        screenHeight: Int,
    ) {
        if (depth > MAX_DEPTH) {
            counter.truncated = true
            return
        }
        if (counter.value >= MAX_NODES) {
            counter.truncated = true
            return
        }

        if (!shouldInclude(node, screenHeight)) {
            traverseChildren(builder, node, depth, indexPath, counter, screenHeight)
            return
        }

        counter.value++
        val indent = "  ".repeat(depth.coerceAtMost(14))
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val vertical = when {
            rect.bottom >= screenHeight * 0.72 -> "bottom"
            rect.top <= screenHeight * 0.2 -> "top"
            else -> "mid"
        }
        val flags = buildList {
            if (node.isClickable) add("clickable")
            if (node.isEditable) add("editable")
            if (node.isScrollable) add("scrollable")
            if (node.isFocusable) add("focusable")
            if (UiNodeHeuristics.isInputLike(node, screenHeight)) add("input-like")
            if (UiNodeHeuristics.isSendLike(node)) add("send-like")
            if (node.isChecked) add("checked")
        }.joinToString(",")

        builder.append(indent)
            .append("- [")
            .append(indexPath)
            .append("] ")
            .append(className.ifBlank { "Node" })
            .append(" @").append(vertical)
        if (viewId.isNotBlank()) builder.append(" id=\"").append(viewId).append('"')
        if (text.isNotBlank()) builder.append(" text=\"").append(text).append('"')
        if (desc.isNotBlank()) builder.append(" desc=\"").append(desc).append('"')
        if (hint.isNotBlank()) builder.append(" hint=\"").append(hint).append('"')
        if (flags.isNotBlank()) builder.append(" (").append(flags).append(')')
        builder.appendLine()

        traverseChildren(builder, node, depth, indexPath, counter, screenHeight)
    }

    private fun traverseChildren(
        builder: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        indexPath: String,
        counter: Counter,
        screenHeight: Int,
    ) {
        for (i in 0 until node.childCount) {
            if (counter.value >= MAX_NODES) {
                counter.truncated = true
                return
            }
            val child = node.getChild(i) ?: continue
            appendNode(builder, child, depth + 1, "$indexPath.$i", counter, screenHeight)
            child.recycle()
        }
    }

    private fun shouldInclude(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        val hasText = !node.text.isNullOrBlank()
            || !node.contentDescription.isNullOrBlank()
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !node.hintText.isNullOrBlank())
        val className = node.className?.toString().orEmpty()
        val isNamedControl = className.contains("Button", ignoreCase = true)
            || className.contains("EditText", ignoreCase = true)
            || className.contains("TextView", ignoreCase = true)
            || className.contains("ImageView", ignoreCase = true)
            || className.contains("WebView", ignoreCase = true)
            || className.contains("RecyclerView", ignoreCase = true)
            || className.contains("ListView", ignoreCase = true)
            || className.contains("FrameLayout", ignoreCase = true)
            || className.contains("Compose", ignoreCase = true)
        return hasText
            || node.isClickable
            || node.isEditable
            || node.isScrollable
            || node.isFocusable
            || isNamedControl
            || UiNodeHeuristics.isInputLike(node, screenHeight)
            || UiNodeHeuristics.isSendLike(node)
    }

    private class Counter(
        var value: Int = 0,
        var truncated: Boolean = false,
    )
}
