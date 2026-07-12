package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.offline.nlu.TimeSlotParser

/**
 * 离线规则解析：将口语指令映射为 [SystemIntentExecutor] 可执行的 action 链。
 *
 * 覆盖通讯、日程、系统设置、打开 App/相机/相册、导航回家等标准 Intent 能力，
 * 无需无障碍点按、无需 Agent 视觉循环。
 */
object SystemIntentLocalParser {
    private val dialToPerson = Regex("""(?:给|向)?(.+?)(?:打电话|打个电话|拨号|拨打)(?:一下|吗)?$""")
    private val dialPrefix = Regex("""^打电话(?:给|到)?(.+)$""")
    private val smsPattern = Regex("""^(给)?(.+?)(发短信|短信)[:：\s]+(.+)$""")
    private val alarmHint = Regex("""闹钟|叫醒|定(?:个|一)?闹钟|设(?:个|置)?闹钟|定时(?:响|叫)""")
    private val calendarHint = Regex("""添加日程|新建日程|日历提醒|记(?:一)?下|安排(?:一)?下|加个日程""")
    private val openAppPattern = Regex("""^(?:打开|启动|运行|进入)\s*(.+)$""")

    private val NAVIGATE_PHRASES = setOf("导航回家", "带我回家", "回家路线", "我要回家", "导航回去")
    private val CAMERA_PHRASES = setOf("打开相机", "拍照", "我要拍照")
    private val GALLERY_PHRASES = setOf("打开相册", "看照片", "我的照片")

    fun parse(command: String, context: Context? = null): List<AgentAction>? {
        val text = command.trim()
        if (text.isEmpty()) return null

        if (context != null) {
            LocalSystemShortcutResolver.match(text, context)?.steps?.let { return it }
        } else {
            LocalSystemShortcutResolver.matchSettingsShortcut(text)?.steps?.let { return it }
        }

        parseDial(text)?.let { return it }
        parseSms(text)?.let { return it }
        parseAlarm(text)?.let { return it }
        parseCalendar(text)?.let { return it }
        parseNavigate(text)?.let { return it }
        parseCamera(text)?.let { return it }
        parseGallery(text)?.let { return it }
        parseOpenApp(text, context)?.let { return it }

        return null
    }

    fun isSystemIntentOnly(steps: List<AgentAction>): Boolean =
        AgentToolRegistry.isSystemIntentOnly(steps)

    private fun parseDial(text: String): List<AgentAction>? {
        val contact = dialToPerson.find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?: dialPrefix.find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?: return null
        return listOf(
            AgentAction(action = "dial_contact", targetText = contact),
            AgentAction(
                action = "finish",
                message = "已为您准备给 $contact 打电话，请确认是否拨出。",
                finished = true,
                waitingForUser = true,
                needsBinaryConfirm = true,
            ),
        )
    }

    private fun parseSms(text: String): List<AgentAction>? {
        val match = smsPattern.find(text) ?: return null
        val contact = match.groupValues[2].trim()
        val body = match.groupValues[4].trim()
        if (contact.isBlank() || body.isBlank()) return null
        return listOf(
            AgentAction(action = "send_sms", targetText = contact, inputText = body),
            AgentAction(action = "finish", message = "已为 $contact 准备短信发送页面", finished = true),
        )
    }

    private fun parseAlarm(text: String): List<AgentAction>? {
        val hasAlarmHint = alarmHint.containsMatchIn(text)
        val parsed = TimeSlotParser.parse(text)
        if (parsed == null) {
            if (hasAlarmHint) {
                return listOf(
                    AgentAction(action = "finish", message = "您想设几点的闹钟？", waitingForUser = true),
                )
            }
            return null
        }
        if (!hasAlarmHint && !text.contains("叫我")) return null

        val title = extractAlarmTitle(text, parsed.hhmm)
        return listOf(
            AgentAction(action = "set_alarm", targetText = parsed.hhmm, inputText = title),
            AgentAction(action = "finish", message = "已打开闹钟设置：${parsed.hhmm}", finished = true),
        )
    }

    private fun parseCalendar(text: String): List<AgentAction>? {
        if (!calendarHint.containsMatchIn(text) && !text.contains("日程")) return null

        val parsed = TimeSlotParser.parse(text)
        val title = extractCalendarTitle(text)
        val input = SystemIntentAiResolver.encodeCalendarInput(
            notes = text.take(60),
            eventTimeIso = parsed?.eventIso,
        )
        val spoken = if (parsed?.eventIso.isNullOrBlank()) {
            "已打开日历新建事件：$title"
        } else {
            "已打开日历新建事件：$title（时间已预填）"
        }
        return listOf(
            AgentAction(action = "add_calendar_event", targetText = title, inputText = input.ifBlank { null }),
            AgentAction(action = "finish", message = spoken, finished = true),
        )
    }

    private fun parseNavigate(text: String): List<AgentAction>? {
        if (text !in NAVIGATE_PHRASES) return null
        return listOf(
            AgentAction(action = "navigate_home"),
            AgentAction(action = "finish", message = "正在为您导航回家。", finished = true),
        )
    }

    private fun parseCamera(text: String): List<AgentAction>? {
        if (text !in CAMERA_PHRASES) return null
        return listOf(
            AgentAction(action = "open_camera"),
            AgentAction(action = "finish", message = "已打开相机", finished = true),
        )
    }

    private fun parseGallery(text: String): List<AgentAction>? {
        if (text !in GALLERY_PHRASES) return null
        return listOf(
            AgentAction(action = "open_gallery"),
            AgentAction(action = "finish", message = "已打开相册", finished = true),
        )
    }

    private fun parseOpenApp(text: String, context: Context?): List<AgentAction>? {
        val appQuery = openAppPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (appQuery.isBlank()) return null
        if (context != null) {
            LocalSystemShortcutResolver.matchOpenApp(text, context)?.steps?.let { return it }
        }
        return listOf(
            AgentAction(action = "open_app", targetText = appQuery),
            AgentAction(action = "finish", message = "已打开：$appQuery", finished = true),
        )
    }

    private fun extractAlarmTitle(text: String, hhmm: String): String? {
        val title = text
            .replace(Regex("""设(个|置)?闹钟"""), "")
            .replace(Regex("""定(个|一)?闹钟"""), "")
            .replace(Regex("""提醒我"""), "")
            .replace(hhmm, "")
            .replace(Regex("""\d{1,2}[:：]\d{2}"""), "")
            .replace(Regex("""[点:：时半刻早晚上午下午凌晨明今天天叫我]"""), "")
            .trim()
        return title.ifBlank { null }
    }

    private fun extractCalendarTitle(text: String): String {
        return when {
            text.contains("开会") -> "开会"
            text.contains("体检") -> "体检"
            text.contains("买菜") -> "买菜"
            text.contains("医院") -> "去医院"
            else -> text
                .replace(Regex("""添加日程|新建日程|日历提醒|记一下|记个|安排一下|提醒我"""), "")
                .trim()
                .take(20)
                .ifBlank { "日程提醒" }
        }
    }

    internal fun resolveAppLabel(context: Context, appQuery: String): String {
        val pkg = InstalledAppResolver.resolvePackage(context, appQuery) ?: return appQuery
        return InstalledAppResolver.getLaunchableApps(context)
            .firstOrNull { it.packageName == pkg }
            ?.label
            ?: appQuery
    }
}
