package com.tetraploid.joyforold.agent

/**
 * 将 find_on_page 返回的标签或 Agent 传入的 click 目标，还原为可匹配的可见文案。
 */
object ClickTargetNormalizer {
    private val annotationSuffix = Regex("""\s*\[(可点击|输入区)]$""")
    private val trailingRoleSuffix = Regex("""(?:title|subtitle|label|text|btn|button)$""", RegexOption.IGNORE_CASE)
    private val gluedViewId = Regex("""^(.+?)([a-z][a-z0-9_]{2,})$""")
    private val htmlTag = Regex("""<[^>]+>""")
    private val htmlEntity = Regex("""&(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);""")

    /** 去掉高德等 App 无障碍树里的 HTML 标签/实体，保留可见文案。 */
    fun stripMarkup(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return text
        text = htmlTag.replace(text, "")
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        text = htmlEntity.replace(text, "")
        return text.trim()
    }

    fun normalize(target: String): String {
        var text = stripMarkup(target)
        if (text.isBlank()) return text

        text = annotationSuffix.replace(text, "").trim()
        text = trailingRoleSuffix.replace(text, "").trim()

        val glued = gluedViewId.find(text)
        if (glued != null) {
            val visible = glued.groupValues[1].trim()
            val idPart = glued.groupValues[2]
            if (visible.length >= 2 && idPart.contains('_')) {
                text = visible
            }
        }
        return text.trim()
    }

    fun clickCandidates(target: String): List<String> {
        val normalized = normalize(target)
        if (normalized.isBlank()) return emptyList()
        return buildList {
            add(normalized)
            if (normalized != target.trim()) add(target.trim())
            // 括号店名：同时尝试「肯德基」短词，提高命中率
            val paren = Regex("""^([^（(]{2,12})[（(]""").find(normalized)?.groupValues?.get(1)?.trim()
            if (!paren.isNullOrBlank() && paren != normalized) add(paren)
            normalized.split(Regex("""\s*\|\s*"""))
                .map { it.trim() }
                .filter { it.length >= 2 }
                .forEach { add(it) }
        }.distinct()
    }
}
