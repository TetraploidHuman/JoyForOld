package com.tetraploid.joyforold.system

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormatter {
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    fun spokenNow(now: LocalDateTime = LocalDateTime.now()): String {
        val hour = now.hour
        val minute = now.minute
        val period = when {
            hour < 6 -> "凌晨"
            hour < 12 -> "上午"
            hour < 13 -> "中午"
            hour < 18 -> "下午"
            else -> "晚上"
        }
        val displayHour = when (hour) {
            0 -> 12
            in 1..12 -> hour
            else -> hour - 12
        }
        val minuteText = if (minute == 0) {
            "${displayHour}点"
        } else {
            "${displayHour}点${minute}分"
        }
        return "现在是$period$minuteText，${now.format(dateFormatter)}，${weekdayLabel(now.dayOfWeek)}"
    }

    private fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
}
