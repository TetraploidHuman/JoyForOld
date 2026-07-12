package com.tetraploid.joyforold.offline.nlu

import android.content.Context
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.SystemIntentAiResolver
import com.tetraploid.joyforold.app.InstalledAppResolver

object SlotExtractor {
    private val OPEN_APP_PREFIX = Regex("""^(?:打开|启动|运行|进入)\s*(.+)$""")
    private val DIAL_PATTERN = Regex("""(?:给)?(.+?)(?:打电话|打个电话|拨号|拨打)""")
    private val CITY_WEATHER = Regex("""(.+?)的天气""")

    data class Slots(
        val app: String? = null,
        val timeHhmm: String? = null,
        val title: String? = null,
        val notes: String? = null,
        val eventTimeIso: String? = null,
        val contact: String? = null,
        val city: String? = null,
    )

    fun extract(intent: String, command: String, context: Context?): Slots {
        val text = command.trim()
        return when (intent) {
            "open_app" -> Slots(app = extractAppName(text, context))
            "set_alarm" -> extractAlarmSlots(text)
            "add_calendar_event" -> extractCalendarSlots(text)
            "dial_contact" -> Slots(contact = extractContact(text))
            "query_weather" -> Slots(city = extractCity(text))
            else -> Slots()
        }
    }

    private fun extractAppName(text: String, context: Context?): String? {
        val query = OPEN_APP_PREFIX.find(text)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { text }
        if (query.isBlank()) return null
        if (context == null) return query
        val pkg = InstalledAppResolver.resolvePackage(context, query) ?: return query
        return InstalledAppResolver.getLaunchableApps(context)
            .firstOrNull { it.packageName == pkg }
            ?.label
            ?: query
    }

    private fun extractAlarmSlots(text: String): Slots {
        val parsed = TimeSlotParser.parse(text) ?: return Slots()
        val title = text
            .replace(Regex("""设(个|置)?闹钟"""), "")
            .replace(Regex("""提醒我"""), "")
            .replace(parsed.hhmm, "")
            .replace(Regex("""\d{1,2}[:：]\d{2}"""), "")
            .replace(Regex("""[点:：时半刻早晚上午下午凌晨明今天天]"""), "")
            .trim()
        return Slots(
            timeHhmm = parsed.hhmm,
            title = title.ifBlank { null },
        )
    }

    private fun extractCalendarSlots(text: String): Slots {
        val parsed = TimeSlotParser.parse(text)
        val title = when {
            text.contains("开会") -> "开会"
            text.contains("体检") -> "体检"
            text.contains("买菜") -> "买菜"
            text.contains("医院") -> "去医院"
            else -> text
                .replace(Regex("""添加日程|记一下|日历提醒|提醒我"""), "")
                .trim()
                .take(20)
                .ifBlank { "日程提醒" }
        }
        return Slots(
            title = title,
            eventTimeIso = parsed?.eventIso,
            notes = text.take(60),
        )
    }

    private fun extractContact(text: String): String? {
        return DIAL_PATTERN.find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    private fun extractCity(text: String): String? {
        CITY_WEATHER.find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }?.let { return it }
        return null
    }
}

object IntentActionMapper {
    private val LABELS = mapOf(
        "open_wifi_settings" to "打开无线网络",
        "open_bluetooth_settings" to "打开蓝牙",
        "open_sound_settings" to "打开声音设置",
        "open_mobile_data_settings" to "打开移动数据",
        "open_location_settings" to "打开定位",
        "open_display_settings" to "打开显示设置",
        "open_settings" to "打开系统设置",
        "open_camera" to "打开相机",
        "open_gallery" to "打开相册",
        "open_weather" to "打开天气",
        "tell_time" to "查看时间",
        "query_weather" to "查询天气",
        "navigate_home" to "导航回家",
        "emergency_help" to "紧急呼救",
        "ask_family_for_help" to "向家人求助",
        "open_health_code" to "打开健康码",
        "open_payment_code" to "打开付款码",
        "open_font_settings" to "调整字体大小",
        "open_app" to "打开应用",
        "set_alarm" to "设闹钟",
        "add_calendar_event" to "添加日程",
        "dial_contact" to "打电话",
    )

    private val SUMMARIES = mapOf(
        "open_wifi_settings" to "已打开无线网络设置",
        "open_bluetooth_settings" to "已打开蓝牙设置",
        "open_sound_settings" to "已打开声音设置",
        "open_mobile_data_settings" to "已打开移动数据设置",
        "open_location_settings" to "已打开定位设置",
        "open_display_settings" to "已打开显示设置",
        "open_settings" to "已打开系统设置",
        "open_camera" to "已打开相机",
        "open_gallery" to "已打开相册",
        "open_weather" to "已打开天气",
        "tell_time" to "正在查看时间",
        "query_weather" to "正在查询天气",
        "navigate_home" to "正在为您导航回家",
        "emergency_help" to "已执行紧急呼救流程",
        "ask_family_for_help" to "已准备向家人发求助短信",
        "open_health_code" to "已尝试打开健康码入口",
        "open_payment_code" to "已尝试打开付款码入口",
        "open_font_settings" to "已打开字体显示设置",
    )

    fun describeIntent(intentId: String, steps: List<AgentAction>): String {
        val action = steps.firstOrNull { !it.action.equals("finish", ignoreCase = true) }?.action ?: intentId
        return describeAction(action, steps)
    }

    fun describeAction(action: String, steps: List<AgentAction>): String {
        LABELS[action]?.let { return it }
        val primary = steps.firstOrNull { !it.action.equals("finish", ignoreCase = true) }
        return when (primary?.action) {
            "open_app" -> "打开${primary.targetText.orEmpty().ifBlank { "应用" }}"
            "dial_contact" -> "给${primary.targetText.orEmpty().ifBlank { "联系人" }}打电话"
            "set_alarm" -> "设${primary.targetText.orEmpty().ifBlank { "闹钟" }}"
            "add_calendar_event" -> "添加日程：${primary.targetText.orEmpty().ifBlank { "提醒" }}"
            else -> action
        }
    }

    fun toSteps(intent: String, command: String, context: Context?): List<AgentAction>? {
        val slots = SlotExtractor.extract(intent, command, context)
        return when (intent) {
            "open_app" -> {
                val app = slots.app ?: return null
                listOf(
                    AgentAction(action = "open_app", targetText = app),
                    AgentAction(action = "finish", message = "已打开：$app", finished = true),
                )
            }
            "set_alarm" -> {
                val time = slots.timeHhmm
                if (time.isNullOrBlank()) {
                    return listOf(
                        AgentAction(action = "finish", message = "您想设几点的闹钟？", waitingForUser = true),
                    )
                }
                listOf(
                    AgentAction(action = "set_alarm", targetText = time, inputText = slots.title),
                    AgentAction(action = "finish", message = "已打开闹钟设置：$time", finished = true),
                )
            }
            "add_calendar_event" -> {
                val title = slots.title ?: "日程提醒"
                val input = SystemIntentAiResolver.encodeCalendarInput(
                    notes = slots.notes.orEmpty(),
                    eventTimeIso = slots.eventTimeIso,
                )
                val spoken = if (slots.eventTimeIso.isNullOrBlank()) {
                    "已打开日历新建事件：$title"
                } else {
                    "已打开日历新建事件：$title（时间已预填）"
                }
                listOf(
                    AgentAction(action = "add_calendar_event", targetText = title, inputText = input.ifBlank { null }),
                    AgentAction(action = "finish", message = spoken, finished = true),
                )
            }
            "dial_contact" -> {
                val contact = slots.contact ?: return null
                listOf(
                    AgentAction(action = "dial_contact", targetText = contact),
                    AgentAction(
                        action = "finish",
                        message = "已为您准备给 $contact 打电话，请确认是否拨出。",
                        waitingForUser = true,
                        needsBinaryConfirm = true,
                    ),
                )
            }
            "query_weather" -> listOf(
                AgentAction(action = "query_weather", targetText = slots.city),
                AgentAction(action = "finish", message = SUMMARIES[intent].orEmpty(), finished = true),
            )
            "tell_time" -> listOf(
                AgentAction(action = "tell_time"),
                AgentAction(action = "finish", message = SUMMARIES[intent].orEmpty(), finished = true),
            )
            in SUMMARIES -> listOf(
                AgentAction(action = intent),
                AgentAction(action = "finish", message = SUMMARIES[intent].orEmpty(), finished = true),
            )
            else -> null
        }
    }
}
