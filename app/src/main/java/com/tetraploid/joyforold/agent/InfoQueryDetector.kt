package com.tetraploid.joyforold.agent

/**
 * 识别「查时间 / 查天气」等信息类口语，避免被「点 xxx」等 UI 动作规则误匹配。
 */
object InfoQueryDetector {
    private val timeQueryPattern = Regex(
        """(?:查看时间|现在几点|几点了|几点钟|几点啦|几点呢|什么时间|报时|什么时间了|现在时间|当前时间)""",
    )
    private val timeLoosePattern = Regex("""几点(钟)?(了|啦|呢)?""")
    private val weatherQueryPattern = Regex("""^(?:查看|查|今天|现在)?天气(?:怎么样|如何)?$""")
    private val weatherCityPattern = Regex("""^(.+?)的天气(?:怎么样|如何)?$""")

    fun isTimeQuery(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (timeQueryPattern.containsMatchIn(trimmed)) return true
        return timeLoosePattern.containsMatchIn(trimmed)
    }

    fun isWeatherQuery(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (weatherQueryPattern.matches(trimmed)) return true
        if (weatherCityPattern.matches(trimmed)) return true
        return trimmed.contains("天气") &&
            (trimmed.contains("怎么样") || trimmed.contains("如何") || trimmed.startsWith("查"))
    }

    fun weatherCity(text: String): String? {
        return weatherCityPattern.find(text.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
