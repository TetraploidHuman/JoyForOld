package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.system.AmapPoiResolver
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 导航地点候选：复用消歧卡片 UI，intentId 编码坐标。 */
object NavPoiPickCodec {
    private const val PREFIX = "navpoi:"

    fun toOption(poi: AmapPoiResolver.Poi, index: Int): DisambiguationOption {
        val id = PREFIX + listOf(
            poi.lat.toString(),
            poi.lon.toString(),
            encode(poi.name),
        ).joinToString("|")
        return DisambiguationOption(
            intentId = id,
            label = poi.displayLabel(index + 1),
            confidence = 1f - index * 0.01f,
        )
    }

    fun parse(intentId: String): AmapPoiResolver.Poi? {
        if (!intentId.startsWith(PREFIX)) return null
        val parts = intentId.removePrefix(PREFIX).split("|", limit = 3)
        if (parts.size < 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val name = decode(parts[2]).ifBlank { return null }
        return AmapPoiResolver.Poi(name = name, lat = lat, lon = lon)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    fun isNavPoiId(intentId: String): Boolean = intentId.startsWith(PREFIX)

    fun matchReply(command: String, options: List<DisambiguationOption>): DisambiguationOption? {
        val text = command.trim()
        if (text.isBlank() || options.isEmpty()) return null
        val spokenCore = stripOrdinalPrefix(text)
        options.firstOrNull { text.contains(it.label, ignoreCase = true) }?.let { return it }
        options.firstOrNull { opt ->
            val poi = parse(opt.intentId) ?: return@firstOrNull false
            text.contains(poi.name, ignoreCase = true) ||
                isFuzzyPlaceMatch(spokenCore, poi.name) ||
                isFuzzyPlaceMatch(spokenCore, labelCoreName(opt.label))
        }?.let { return it }
        indexFromSpeech(text)?.let { idx ->
            if (idx in options.indices) return options[idx]
        }
        return null
    }

    internal fun labelCoreName(label: String): String =
        label.trim()
            .replace(Regex("""^\d+\.\s*"""), "")
            .substringBefore(" · ")
            .trim()

    internal fun isFuzzyPlaceMatch(spoken: String, candidate: String): Boolean {
        val a = canonicalPlaceAlias(spoken)
        val b = canonicalPlaceAlias(candidate)
        if (a.isBlank() || b.isBlank()) return false
        if (b.contains(a, ignoreCase = true) || a.contains(b, ignoreCase = true)) return true
        if (placeAliasClose(a, b)) return true
        if (homophoneVariants(a).any { placeAliasClose(it, b) }) return true
        return false
    }

    private fun placeAliasClose(a: String, b: String): Boolean {
        if (a == b) return true
        if (editDistance(a, b) <= 1 && minOf(a.length, b.length) >= 2) return true
        if (a.length >= 2 && b.length >= 2) {
            val suffixes = listOf("第一中学", "一中", "中学", "学校", "店", "餐厅")
            for (suffix in suffixes) {
                if (a.endsWith(suffix) && b.endsWith(suffix)) {
                    val prefixA = a.removeSuffix(suffix)
                    val prefixB = b.removeSuffix(suffix)
                    if (prefixA.isNotBlank() && prefixB.isNotBlank()) {
                        if (editDistance(prefixA, prefixB) <= 1) return true
                        if (homophoneVariants(prefixA).any { editDistance(it, prefixB) <= 1 }) return true
                    }
                }
            }
        }
        return false
    }

    internal fun canonicalPlaceAlias(name: String): String {
        val n = normalizePlaceName(name)
        Regex("""^(.+?)(?:第)?一(?:级)?中(?:学|学)?(?:学校)?$""").find(n)?.let { match ->
            return "${match.groupValues[1]}一中"
        }
        return n
    }

    private fun homophoneVariants(name: String): List<String> {
        if (name.isEmpty()) return emptyList()
        val swaps = mapOf('贵' to '桂', '桂' to '贵', '杨' to '阳', '阳' to '杨')
        val out = mutableListOf<String>()
        name.forEachIndexed { index, ch ->
            swaps[ch]?.let { alt ->
                out += name.substring(0, index) + alt + name.substring(index + 1)
            }
        }
        return out
    }

    private fun normalizePlaceName(name: String): String =
        name.trim()
            .replace(Regex("""[省市区县镇乡]"""), "")
            .replace(Regex("""\s+"""), "")

    private fun stripOrdinalPrefix(text: String): String =
        text.trim().replace(Regex("""^(?:去|到|导航到|带我去)?\s*"""), "")

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var prev = dp[0]
            dp[0] = i + 1
            for (j in b.indices) {
                val tmp = dp[j + 1]
                val cost = if (a[i] == b[j]) 0 else 1
                dp[j + 1] = minOf(dp[j + 1] + 1, dp[j] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun indexFromSpeech(text: String): Int? {
        Regex("""第\s*([一二三四五六七八九123456789])\s*个?""").find(text)?.groupValues?.get(1)?.let {
            return when (it) {
                "一", "1" -> 0
                "二", "2" -> 1
                "三", "3" -> 2
                "四", "4" -> 3
                "五", "5" -> 4
                "六", "6" -> 5
                "七", "7" -> 6
                "八", "8" -> 7
                "九", "9" -> 8
                else -> null
            }
        }
        text.toIntOrNull()?.let { n ->
            if (n in 1..9) return n - 1
        }
        return null
    }
}

private fun AmapPoiResolver.Poi.displayLabel(ordinal: Int): String {
    val parts = mutableListOf<String>()
    parts += "$ordinal. $name"
    address.takeIf { it.isNotBlank() && it != name }?.let { parts += it }
    distanceMeters?.let { meters ->
        parts += if (meters < 1000) {
            "${meters}米"
        } else {
            String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)
        }
    }
    return parts.joinToString(" · ")
}
