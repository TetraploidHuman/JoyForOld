package com.tetraploid.joyforold.agent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class StructuredPageSnapshot(
    val packageName: String,
    val appHint: String,
    val clickables: List<String>,
    val editables: List<String>,
    val visibleTexts: List<String>,
    val sendButtons: List<String>,
    val fingerprint: String,
) {
    fun toCompactSummary(maxChars: Int = 2_400): String {
        val snap = this
        val raw = buildString {
            appendLine("=== 页面快览 ===")
            if (snap.appHint.isNotBlank()) appendLine(snap.appHint)
            appendLine("package: ${snap.packageName}")
            appendLine("可点击(${snap.clickables.size}): ${snap.clickables.take(50).joinToString(" | ")}")
            PageSnapshotHints.linesFor(snap).forEach { hint ->
                appendLine(hint)
            }
            if (snap.sendButtons.isNotEmpty()) {
                appendLine("发送相关(${snap.sendButtons.size}): ${snap.sendButtons.joinToString(" | ")}")
            }
            appendLine("可输入(${snap.editables.size}): ${snap.editables.take(20).joinToString(" | ")}")
            appendLine("可见文字(${snap.visibleTexts.size}): ${snap.visibleTexts.take(50).joinToString(" | ")}")
        }.trimEnd()
        return if (raw.length <= maxChars) {
            raw
        } else {
            raw.take(maxChars) + "\n...（页面快览已截断，共 ${raw.length} 字）"
        }
    }

    fun toMinimalSummary(): String = buildString {
        if (appHint.isNotBlank()) {
            append(appHint)
        } else {
            append(packageName)
        }
        append(" | 可点击 ").append(clickables.size)
        append(" | 可输入 ").append(editables.size)
        if (sendButtons.isNotEmpty()) {
            append(" | 发送相关 ").append(sendButtons.size)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("app_hint", appHint)
        put("clickables", JSONArray(clickables))
        put("editables", JSONArray(editables))
        put("visible_texts", JSONArray(visibleTexts))
        put("send_buttons", JSONArray(sendButtons))
        put("fingerprint", fingerprint)
    }

    companion object {
        fun fromJson(json: JSONObject): StructuredPageSnapshot = StructuredPageSnapshot(
            packageName = json.optString("package_name"),
            appHint = json.optString("app_hint"),
            clickables = json.optJSONArray("clickables").toStringList(),
            editables = json.optJSONArray("editables").toStringList(),
            visibleTexts = json.optJSONArray("visible_texts").toStringList(),
            sendButtons = json.optJSONArray("send_buttons").toStringList(),
            fingerprint = json.optString("fingerprint"),
        )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) add(optString(i))
            }
        }
    }
}

object PageObservation {
    private const val MAX_WALK_NODES = 1_500
    private const val MAX_DEPTH = 55
    const val COMPACT_SUMMARY_MAX_CHARS = 2_400

    fun capture(root: AccessibilityNodeInfo): StructuredPageSnapshot {
        val screenHeight = UiNodeHeuristics.screenHeight(root)
        val clickables = linkedSetOf<String>()
        val editables = linkedSetOf<String>()
        val visibleTexts = linkedSetOf<String>()
        val sendButtons = linkedSetOf<String>()
        var walked = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || walked >= MAX_WALK_NODES) return
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
                if (clickLabel.isNotBlank()) clickables += clickLabel
            }

            if (UiNodeHeuristics.isSendLike(node)) {
                sendButtons += UiNodeHeuristics.clickableLabel(node).ifBlank { "发送" }
            }

            if (UiNodeHeuristics.isInputLike(node, screenHeight)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val pos = if (rect.bottom >= screenHeight * 0.72) "底部" else "中部"
                editables += buildString {
                    append(className.ifBlank { "输入区" })
                    append("(").append(pos).append(")")
                    if (hint.isNotBlank()) append(" hint=\"").append(hint).append('"')
                    if (desc.isNotBlank()) append(" desc=\"").append(desc).append('"')
                    if (text.isNotBlank()) append(" text=\"").append(text).append('"')
                    if (viewId.isNotBlank()) append(" id=\"").append(viewId).append('"')
                }
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
            pkg.contains("tencent.mobileqq") -> "当前为 QQ"
            pkg.contains("com.tencent.mm") -> "当前为微信"
            else -> ""
        }

        val fingerprint = buildString {
            append(pkg).append('|')
            append(clickables.size).append('|')
            append(editables.size).append('|')
            append(visibleTexts.take(20).joinToString(","))
        }

        return StructuredPageSnapshot(
            packageName = pkg,
            appHint = appHint,
            clickables = clickables.toList(),
            editables = editables.toList(),
            visibleTexts = visibleTexts.toList(),
            sendButtons = sendButtons.toList(),
            fingerprint = fingerprint,
        )
    }

    fun diff(previous: StructuredPageSnapshot?, current: StructuredPageSnapshot): String {
        if (previous == null) {
            return "首次观察：package=${current.packageName}，可点击 ${current.clickables.size} 项，可输入 ${current.editables.size} 项。"
        }

        val packageChanged = previous.packageName != current.packageName
        val newClickables = current.clickables.filter { it !in previous.clickables.toSet() }
        val removedClickables = previous.clickables.filter { it !in current.clickables.toSet() }
        val newTexts = current.visibleTexts.filter { it !in previous.visibleTexts.toSet() }
        val newEditables = current.editables.filter { it !in previous.editables.toSet() }
        val unchanged = previous.fingerprint == current.fingerprint

        return buildString {
            appendLine("=== 页面变化 ===")
            if (packageChanged) {
                appendLine("应用切换：${previous.packageName} → ${current.packageName}")
            } else {
                appendLine("应用未变：${current.packageName}")
            }
            if (unchanged) {
                appendLine("页面指纹未变（可能仍在同一屏或变化较小）")
            }
            if (newClickables.isNotEmpty()) {
                appendLine("新增可点击(${newClickables.size}): ${newClickables.take(15).joinToString(" | ")}")
            }
            if (removedClickables.isNotEmpty()) {
                appendLine("消失可点击(${removedClickables.size}): ${removedClickables.take(15).joinToString(" | ")}")
            }
            if (newEditables.isNotEmpty()) {
                appendLine("新增输入区: ${newEditables.take(8).joinToString(" | ")}")
            }
            if (newTexts.isNotEmpty()) {
                appendLine("新增可见文字(${newTexts.size}): ${newTexts.take(12).joinToString(" | ")}")
            }
            if (!packageChanged && newClickables.isEmpty() && removedClickables.isEmpty() &&
                newTexts.isEmpty() && newEditables.isEmpty() && !unchanged
            ) {
                appendLine("页面有更新，但可点击/文字列表变化不明显，请结合快览判断。")
            }
        }.trimEnd()
    }
}
