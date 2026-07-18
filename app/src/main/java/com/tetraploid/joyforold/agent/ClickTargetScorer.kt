package com.tetraploid.joyforold.agent

import kotlin.math.abs
import kotlin.math.min

/**
 * 无障碍 click 目标打分：**只看文案匹配质量**（+ 合理控件尺寸）。
 *
 * 「越近越好 / 列表越靠上越好」属于高德 Web API POI 候选
 *（[com.tetraploid.joyforold.system.AmapPoiResolver] 的 around + sortrule=distance），
 * **不要**混进这里——否则微信联系人会点到上方名字相近的人。
 *
 * 高德 App 内「导航 / 路线 / 公里」等 CTA 仍保留专用加权。
 */
object ClickTargetScorer {
    data class Candidate(
        val query: String,
        val nodeText: String,
        val displayLabel: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val visibleToUser: Boolean,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    fun score(c: Candidate): Int {
        val query = c.query.trim().lowercase()
        if (query.isEmpty()) return Int.MIN_VALUE

        val nodeText = c.nodeText.lowercase()
        val display = c.displayLabel.lowercase().ifBlank { nodeText }
        val primary = primaryName(display)

        var score = textMatchScore(query, nodeText, display, primary)
        score += amapCtaBonus(query, nodeText, c)
        if (c.visibleToUser) score += 8_000
        score += sizeBonus(c)
        // 极弱平局决胜（非「最近」语义）：避免完全同分抖动
        score += (100 - (c.top / 100).coerceIn(0, 100))
        return score
    }

    fun matches(query: String, nodeText: String): Boolean {
        val lower = query.trim().lowercase()
        if (lower.isEmpty()) return false
        val text = nodeText.lowercase()
        if (text.contains(lower)) return true
        if (lower.contains("发送")) return false
        val tokens = lower.split(Regex("\\s+")).filter { it.length >= 2 }
        return tokens.any { text.contains(it) }
    }

    private fun textMatchScore(
        query: String,
        nodeText: String,
        display: String,
        primary: String,
    ): Int {
        when {
            display == query || nodeText == query || primary == query ->
                return 600_000
            display.startsWith(query) || primary.startsWith(query) ->
                return 400_000 + lengthCloseness(primary.ifBlank { display }, query)
            Regex("""^${Regex.escape(query)}([\s|：:：·\-—]|$)""")
                .containsMatchIn(display) ->
                return 400_000 + lengthCloseness(display, query)
            nodeText.contains(query) || display.contains(query) -> {
                val host = when {
                    display.contains(query) -> display
                    else -> nodeText
                }
                return 200_000 + lengthCloseness(host, query)
            }
            else -> {
                val tokens = query.split(Regex("\\s+")).filter { it.length >= 2 }
                val hit = tokens.count { nodeText.contains(it) || display.contains(it) }
                return hit * 2_000
            }
        }
    }

    private fun lengthCloseness(host: String, query: String): Int {
        val extra = abs(host.length - query.length).coerceAtMost(40)
        return (40 - extra) * 200
    }

    private fun primaryName(display: String): String {
        val cut = display
            .substringBefore(' ')
            .substringBefore('｜')
            .substringBefore('|')
            .substringBefore('：')
            .substringBefore(':')
            .substringBefore('·')
            .trim()
        return cut.ifBlank { display }
    }

    private fun sizeBonus(c: Candidate): Int {
        val w = c.width
        val h = c.height
        if (w <= 0 || h <= 0) return -50_000
        val area = w.toLong() * h
        val screenArea = c.screenWidth.toLong().coerceAtLeast(1) * c.screenHeight.toLong().coerceAtLeast(1)
        if (c.screenWidth > 0 && c.screenHeight > 0 && area > screenArea / 2) {
            return -120_000
        }
        return when {
            h in 60..360 && w >= 200 -> 25_000
            h in 40..480 -> 10_000
            h > 900 || (c.screenWidth > 0 && w > c.screenWidth * 0.95) -> -40_000
            else -> 0
        }
    }

    /** 高德 App 内按钮/距离文案，与 Web API「最近」候选无关。 */
    private fun amapCtaBonus(query: String, nodeText: String, c: Candidate): Int {
        var bonus = 0
        if (query == "导航") {
            if (nodeText.trim() == "导航") bonus += 80_000
            bonus += c.bottom / 20
            if (nodeText.contains("语音") || nodeText.contains("设置")) bonus -= 80_000
        }
        if (query == "开始导航" && nodeText.contains("开始导航")) bonus += 60_000
        if (query == "路线") {
            if (nodeText.trim() == "路线" || nodeText.endsWith(" 路线")) bonus += 60_000
            bonus += c.left / 8
            bonus += c.bottom / 20
        }
        if (query.contains("公里")) {
            if (nodeText.contains("附近")) bonus -= 200_000
            if (Regex("""\d+\.\d+\s*公里""").containsMatchIn(nodeText)) bonus += 40_000
        }
        if ((nodeText.contains('(') || nodeText.contains('（')) &&
            query.length >= 2 &&
            nodeText.contains(query.take(min(query.length, 8)))
        ) {
            bonus += 30_000
        }
        return bonus
    }
}
