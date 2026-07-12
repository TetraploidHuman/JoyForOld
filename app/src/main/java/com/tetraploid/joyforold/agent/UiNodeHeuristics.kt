package com.tetraploid.joyforold.agent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object UiNodeHeuristics {
    private val inputIdKeywords = listOf(
        "input", "edit", "editor", "chat", "message", "msg", "compose", "write", "et_",
    )
    private val sendKeywords = listOf("发送", "send", "发表", "送出")
    private val chatKeywords = listOf("聊天", "消息", "联系人", "会话", "输入", "发送")

    fun displayLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty().trim()
        if (text.isNotBlank()) return text
        val desc = node.contentDescription?.toString().orEmpty().trim()
        if (desc.isNotBlank()) return desc
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString().orEmpty().trim()
            if (hint.isNotBlank()) return hint
        }
        return node.viewIdResourceName?.substringAfterLast('/').orEmpty().trim()
    }

    fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        append(displayLabel(node))
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (viewId.isNotBlank() && !displayLabel(node).equals(viewId, ignoreCase = true)) {
            append(viewId)
        }
    }.trim()

    /** 系统输入法（Gboard/搜狗等）键盘按键，不是应用内真实输入框。 */
    fun isImeKeyboardNode(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty().lowercase()
        if (viewId.startsWith("key_pos_") ||
            viewId.contains("keyboard_") ||
            viewId.contains("input_method_") ||
            viewId == "inputarea" ||
            viewId == "input_area"
        ) {
            return true
        }
        if (viewId.matches(Regex("^[a-z]\\d{2}$")) || viewId.matches(Regex("^[a-z][0-9]{2}$"))) {
            return true
        }
        val desc = node.contentDescription?.toString().orEmpty().trim()
        if (desc in setOf("空格键", "删除", "Shift", "Enter 键", "符号键盘", "QWERTY", "?123")) {
            return true
        }
        if (desc.length == 1 && desc[0].isLetterOrDigit()) {
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("FrameLayout", ignoreCase = true) && node.isClickable) return true
        }
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Keyboard", ignoreCase = true)) return true
        return false
    }

    fun isInputLike(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        if (isImeKeyboardNode(node)) return false
        if (node.isEditable) return true
        val className = node.className?.toString().orEmpty()
        if (className.contains("EditText", ignoreCase = true)) return true
        if (className.contains("Edit", ignoreCase = true) && node.isFocusable) return true
        if (supportsSetText(node)) return true

        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (inputIdKeywords.any { viewId.contains(it) }) return true

        val label = nodeLabel(node).lowercase()
        if (node.isFocusable && inputIdKeywords.any { label.contains(it) || viewId.contains(it) }) {
            return true
        }

        if (node.isFocusable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val inBottom = rect.bottom >= screenHeight * 0.72
            val reasonableHeight = rect.height() in 28..420
            val hasHint = label.contains("输入") || label.contains("消息") || label.contains("说点什么")
            if (inBottom && reasonableHeight && (hasHint || className.contains("Text", ignoreCase = true))) {
                return true
            }
        }
        return false
    }

    fun isSendLike(node: AccessibilityNodeInfo): Boolean {
        val label = nodeLabel(node).lowercase()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val hasSendHint = sendKeywords.any { label.contains(it.lowercase()) || viewId.contains(it.lowercase()) }
        if (!hasSendHint) return false
        return node.isClickable || node.parent?.isClickable == true
    }

    fun clickableLabel(node: AccessibilityNodeInfo): String {
        val label = nodeLabel(node)
        if (label.isNotBlank()) return label

        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
        return when {
            isSendLike(node) -> "发送"
            viewId.contains("send", ignoreCase = true) -> "发送(按钮)"
            viewId.contains("input", ignoreCase = true) -> "输入(区域)"
            className.isNotBlank() -> "$className(可点)"
            else -> ""
        }
    }

    fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val actions = node.actionList ?: return false
            return actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
        return false
    }

    fun inputScore(node: AccessibilityNodeInfo, screenHeight: Int): Int {
        if (!isInputLike(node, screenHeight)) return Int.MIN_VALUE
        val rect = Rect()
        node.getBoundsInScreen(rect)
        var score = rect.bottom * 3 + rect.width()
        if (node.isEditable) score += 5_000
        if (supportsSetText(node)) score += 3_000
        val label = nodeLabel(node).lowercase()
        if (label.contains("输入") || label.contains("消息")) score += 1_000
        return score
    }

    fun chatKeywordHits(root: AccessibilityNodeInfo): Int {
        var hits = 0
        var walked = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 50 || walked >= 400) return
            walked++
            val combined = nodeLabel(node).lowercase()
            if (chatKeywords.any { combined.contains(it) }) hits++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(root, 0)
        return hits
    }

    fun screenHeight(root: AccessibilityNodeInfo): Int {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        return rect.height().coerceAtLeast(1)
    }
}
