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
        val text = ClickTargetNormalizer.stripMarkup(node.text?.toString().orEmpty())
        if (text.isNotBlank()) return text
        val desc = ClickTargetNormalizer.stripMarkup(node.contentDescription?.toString().orEmpty())
        if (desc.isNotBlank()) return desc
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = ClickTargetNormalizer.stripMarkup(node.hintText?.toString().orEmpty())
            if (hint.isNotBlank()) return hint
        }
        return node.viewIdResourceName?.substringAfterLast('/').orEmpty().trim()
    }

    /**
     * 供 find/click 匹配：合并 text + contentDescription（高德「路线」只在 desc，
     * 店名常带 HTML text），避免只看 text 时漏匹配。
     */
    fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        val text = ClickTargetNormalizer.stripMarkup(node.text?.toString().orEmpty())
        val desc = ClickTargetNormalizer.stripMarkup(node.contentDescription?.toString().orEmpty())
        if (text.isNotBlank()) append(text)
        if (desc.isNotBlank() && !text.contains(desc)) {
            if (isNotEmpty()) append(' ')
            append(desc)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = ClickTargetNormalizer.stripMarkup(node.hintText?.toString().orEmpty())
            if (hint.isNotBlank() && !contains(hint)) {
                if (isNotEmpty()) append(' ')
                append(hint)
            }
        }
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val visible = toString().trim()
        if (viewId.isNotBlank() && !visible.equals(viewId, ignoreCase = true)) {
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

    /**
     * 仅自身可见文案（不含 viewId），用于判断是否需要下钻子节点。
     */
    fun ownHumanLabel(node: AccessibilityNodeInfo): String = buildString {
        val text = ClickTargetNormalizer.stripMarkup(node.text?.toString().orEmpty())
        val desc = ClickTargetNormalizer.stripMarkup(node.contentDescription?.toString().orEmpty())
        if (text.isNotBlank()) append(text)
        if (desc.isNotBlank() && !text.contains(desc)) {
            if (isNotEmpty()) append(' ')
            append(desc)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = ClickTargetNormalizer.stripMarkup(node.hintText?.toString().orEmpty())
            if (hint.isNotBlank() && !contains(hint)) {
                if (isNotEmpty()) append(' ')
                append(hint)
            }
        }
    }.trim()

    /**
     * 可点击节点自身无文案时（微信会话行常只有 id=cj1），汇总子节点名字供快览/选型。
     * 纯函数便于单测；[clickableLabel] 从无障碍树采文案后调用。
     */
    fun composeClickableLabel(
        ownHumanLabel: String,
        ownViewId: String,
        ownClassName: String,
        descendantHumanLabels: List<String>,
        isSendLike: Boolean = false,
    ): String {
        if (ownHumanLabel.isNotBlank()) {
            return buildString {
                append(ownHumanLabel)
                if (ownViewId.isNotBlank() && !ownHumanLabel.contains(ownViewId, ignoreCase = true)) {
                    append(ownViewId)
                }
            }.trim()
        }

        // 可点击标签优先取首个有效人名/短标题，避免会话行把「最后一条消息」拼进来
        val aggregated = joinDescendantLabels(descendantHumanLabels, maxParts = 1)
        if (aggregated.isNotBlank()) return aggregated

        return when {
            isSendLike -> "发送"
            ownViewId.contains("send", ignoreCase = true) -> "发送(按钮)"
            ownViewId.contains("input", ignoreCase = true) -> "输入(区域)"
            ownViewId.isNotBlank() -> ownViewId
            ownClassName.isNotBlank() -> "$ownClassName(可点)"
            else -> ""
        }
    }

    /** 从子节点可见文案里挑短标题（联系人名优先），去掉时间戳等噪音。 */
    fun joinDescendantLabels(
        labels: List<String>,
        maxParts: Int = 2,
        maxLen: Int = 60,
    ): String {
        val parts = mutableListOf<String>()
        for (raw in labels) {
            if (parts.size >= maxParts) break
            val candidate = ClickTargetNormalizer.stripMarkup(raw).trim()
            if (candidate.length !in 1..40) continue
            if (looksLikeCrypticId(candidate) || looksLikeListNoise(candidate)) continue
            if (parts.any { it.contains(candidate) || candidate.contains(it) }) continue
            parts += candidate
        }
        return parts.joinToString(" ").take(maxLen).trim()
    }

    fun looksLikeCrypticId(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        // 微信混淆 id：cj1、jha、d98、kbq 等
        if (v.matches(Regex("""^[a-zA-Z]\w{0,3}$"""))) return true
        if (v.matches(Regex("""^[a-z]{1,3}\d{1,3}$""", RegexOption.IGNORE_CASE))) return true
        return false
    }

    private fun looksLikeListNoise(value: String): Boolean {
        val v = value.trim()
        if (v.matches(Regex("""^(昨天|前天|周一|周二|周三|周四|周五|周六|周日|星期[一二三四五六日天])$"""))) {
            return true
        }
        if (v.matches(Regex("""^(上午|下午|晚上)?\d{1,2}:\d{2}$"""))) return true
        if (v.matches(Regex("""^\d{1,2}月\d{1,2}日$"""))) return true
        return false
    }

    private fun collectDescendantHumanLabels(
        node: AccessibilityNodeInfo,
        // 微信会话行：可点 cj1 → cj0 → … → kbq 名字，常见深度 6+
        maxDepth: Int = 8,
        maxWalk: Int = 60,
        maxParts: Int = 6,
    ): List<String> {
        val out = mutableListOf<String>()
        var walked = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || walked >= maxWalk || out.size >= maxParts) return
            walked++
            // 其它可点子树各自有标签，避免把整块列表拼进一行
            if (depth > 0 && n.isClickable) return

            val human = ownHumanLabel(n)
            if (human.isNotBlank()) out += human

            if (out.size >= maxParts) return
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
                if (out.size >= maxParts) return
            }
        }
        walk(node, 0)
        return out
    }

    fun clickableLabel(node: AccessibilityNodeInfo): String {
        val ownHuman = ownHumanLabel(node)
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
        val descendants = if (ownHuman.isBlank()) {
            collectDescendantHumanLabels(node)
        } else {
            emptyList()
        }
        return composeClickableLabel(
            ownHumanLabel = ownHuman,
            ownViewId = viewId,
            ownClassName = className,
            descendantHumanLabels = descendants,
            isSendLike = isSendLike(node),
        )
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
