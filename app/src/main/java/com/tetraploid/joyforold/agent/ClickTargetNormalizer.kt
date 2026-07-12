package com.tetraploid.joyforold.agent

/**
 * 将 find_on_page 返回的标签或 Agent 传入的 click 目标，还原为可匹配的可见文案。
 */
object ClickTargetNormalizer {
    private val annotationSuffix = Regex("""\s*\[(可点击|输入区)]$""")
    private val trailingRoleSuffix = Regex("""(?:title|subtitle|label|text|btn|button)$""", RegexOption.IGNORE_CASE)
    private val gluedViewId = Regex("""^(.+?)([a-z][a-z0-9_]{2,})$""")

    fun normalize(target: String): String {
        var text = target.trim()
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
            normalized.split(Regex("""\s*\|\s*"""))
                .map { it.trim() }
                .filter { it.length >= 2 }
                .forEach { add(it) }
        }.distinct()
    }
}
