package com.tetraploid.joyforold.agent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object UiPageProbe {
    private const val MAX_WALK_NODES = 1_500
    private const val MAX_DEPTH = 55
    private const val MAX_LIST_ITEMS = 80

    fun buildSummary(root: AccessibilityNodeInfo): String {
        val screenHeight = UiNodeHeuristics.screenHeight(root)
        val clickables = linkedSetOf<String>()
        val editables = linkedSetOf<String>()
        val visibleTexts = linkedSetOf<String>()
        val sendButtons = linkedSetOf<String>()
        var walked = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || walked >= MAX_WALK_NODES) {
                truncated = true
                return
            }
            walked++

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val className = node.className?.toString()?.substringAfterLast('.').orEmpty()

            val label = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                hint.isNotBlank() -> hint
                viewId.isNotBlank() -> viewId
                else -> ""
            }

            if (label.isNotBlank() && label.length <= 100) {
                visibleTexts += label
            }

            if (node.isClickable) {
                val clickLabel = UiNodeHeuristics.clickableLabel(node)
                if (clickLabel.isNotBlank()) {
                    clickables += clickLabel
                }
            }

            if (UiNodeHeuristics.isSendLike(node)) {
                sendButtons += UiNodeHeuristics.clickableLabel(node).ifBlank { "发送" }
            }

            if (UiNodeHeuristics.isInputLike(node, screenHeight)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val pos = if (rect.bottom >= screenHeight * 0.72) "底部" else "中部"
                val editableLabel = buildString {
                    append(className.ifBlank { "输入区" })
                    append("(").append(pos).append(")")
                    if (hint.isNotBlank()) append(" hint=\"").append(hint).append('"')
                    if (desc.isNotBlank()) append(" desc=\"").append(desc).append('"')
                    if (text.isNotBlank()) append(" text=\"").append(text).append('"')
                    if (viewId.isNotBlank()) append(" id=\"").append(viewId).append('"')
                }
                editables += editableLabel
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }

        walk(root, 0)

        val pkg = root.packageName?.toString().orEmpty()
        val appHint = when {
            pkg.contains("tencent.mobileqq") -> "当前为 QQ，发消息：先点联系人/会话 → 点底部输入区 → 输入 → 点发送"
            pkg.contains("com.tencent.mm") -> "当前为微信，发消息：进入聊天 → 点输入框 → 输入 → 点发送"
            else -> ""
        }

        return buildString {
            appendLine("=== 页面快览 ===")
            if (appHint.isNotBlank()) appendLine(appHint)
            appendLine("可点击(${clickables.size}): ${clickables.take(MAX_LIST_ITEMS).joinToString(" | ")}")
            if (sendButtons.isNotEmpty()) {
                appendLine("发送相关(${sendButtons.size}): ${sendButtons.joinToString(" | ")}")
            }
            appendLine("可输入(${editables.size}): ${editables.take(30).joinToString(" | ")}")
            appendLine(
                "可见文字(${visibleTexts.size}): ${visibleTexts.take(MAX_LIST_ITEMS).joinToString(" | ")}",
            )
            if (truncated) {
                appendLine("注意: 页面节点较多，以上为节选；请结合结构树继续判断。")
            }
        }
    }

    fun windowScore(root: AccessibilityNodeInfo): Int {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        var score = (rect.width() * rect.height()).coerceAtLeast(0) / 10_000
        val screenHeight = UiNodeHeuristics.screenHeight(root)
        var walked = 0
        var inputCount = 0
        var sendCount = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || walked >= 300) return
            walked++
            if (UiNodeHeuristics.isInputLike(node, screenHeight)) inputCount++
            if (UiNodeHeuristics.isSendLike(node)) sendCount++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }

        walk(root, 0)
        score += inputCount * 900
        score += sendCount * 700
        score += UiNodeHeuristics.chatKeywordHits(root) * 80
        return score
    }
}
