package com.tetraploid.joyforold.offline.nlu

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 中文相对时间解析（规则槽位），供离线 NLU 使用。
 */
object TimeSlotParser {
    private val CLOCK_PATTERN = Regex("""(\d{1,2})[:：](\d{2})""")
    private val HOUR_MINUTE_PATTERN = Regex(
        """(?:(上午|早上|清晨|凌晨|下午|晚上|傍晚)?)\s*([0-9]{1,2}|[零一二两三四五六七八九十]+)[点:：时](半|一刻|[0-9]{1,2}|[零一二两三四五六七八九十]+)?""",
    )
    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    data class ParsedTime(
        val hhmm: String,
        val eventIso: String? = null,
    )

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedTime? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        CLOCK_PATTERN.find(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            return buildParsed(hour, minute, trimmed, now)
        }

        HOUR_MINUTE_PATTERN.find(trimmed)?.let { match ->
            val period = match.groupValues[1]
            val hourToken = match.groupValues[2]
            val hourRaw = hourToken.toIntOrNull() ?: parseChineseNumber(hourToken) ?: return@let
            val minuteToken = match.groupValues[3]
            val minuteRaw = when (minuteToken) {
                "半" -> 30
                "一刻" -> 15
                "" -> 0
                else -> minuteToken.toIntOrNull() ?: parseChineseNumber(minuteToken) ?: 0
            }
            val hour = adjustHourByPeriod(hourRaw, period)
            return buildParsed(hour, minuteRaw, trimmed, now)
        }

        return null
    }

    private fun buildParsed(hour: Int, minute: Int, source: String, now: LocalDateTime): ParsedTime? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val hhmm = "%d:%02d".format(hour, minute)
        val date = resolveDate(source, now.toLocalDate())
        val dateTime = LocalDateTime.of(date, LocalTime.of(hour, minute))
        val iso = dateTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return ParsedTime(hhmm = hhmm, eventIso = iso)
    }

    private fun resolveDate(text: String, today: LocalDate): LocalDate {
        return when {
            text.contains("后天") -> today.plusDays(2)
            text.contains("明天") || text.contains("明早") || text.contains("明晚") -> today.plusDays(1)
            text.contains("今天") || text.contains("今晚") || text.contains("今早") -> today
            else -> today
        }
    }

    private fun adjustHourByPeriod(hour: Int, period: String): Int {
        if (period.isBlank()) return hour
        return when {
            period.contains("下午") || period.contains("晚上") || period.contains("傍晚") -> {
                if (hour in 1..11) hour + 12 else hour
            }
            else -> hour
        }
    }

    private fun parseChineseNumber(token: String): Int? {
        if (token.isBlank()) return null
        if (token.length == 1 && token[0] in CN_DIGITS) return CN_DIGITS[token[0]]
        if (token == "十") return 10
        if (token.startsWith("十") && token.length == 2) {
            return 10 + (CN_DIGITS[token[1]] ?: return null)
        }
        if (token.endsWith("十") && token.length == 2) {
            return (CN_DIGITS[token[0]] ?: return null) * 10
        }
        return null
    }
}
