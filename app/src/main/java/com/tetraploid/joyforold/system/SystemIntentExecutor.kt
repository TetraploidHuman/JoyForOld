package com.tetraploid.joyforold.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.net.toUri
import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import java.util.Locale

object SystemIntentExecutor {
    fun execute(context: Context, action: String, targetText: String?, inputText: String?): ActionExecutionResult {
        return when (action.lowercase(Locale.getDefault())) {
            "dial_contact" -> dialContact(context, targetText)
            "send_sms" -> sendSms(context, targetText, inputText)
            "set_alarm" -> setAlarm(context, targetText, inputText)
            "add_calendar_event" -> addCalendarEvent(context, targetText, inputText)
            "open_camera" -> launchSimpleIntent(context, Intent("android.media.action.IMAGE_CAPTURE"), "已打开相机")
            "open_gallery" -> launchSimpleIntent(
                context,
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media"), "image/*"),
                "已打开相册",
            )
            "open_weather" -> openWeather(context)
            "open_health_code" -> openByAlias(context, "支付宝", "已尝试打开健康码入口应用（支付宝）")
            "open_payment_code" -> openByAlias(context, "支付宝", "已尝试打开付款码入口应用（支付宝）")
            "open_font_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_DISPLAY_SETTINGS), "已打开显示设置")
            "navigate_home" -> navigateHome(context)
            "read_unread_messages" -> readUnreadMessages(context)
            "ask_family_for_help" -> askFamilyForHelp(context, targetText)
            "emergency_help" -> emergencyHelp(context)
            else -> ActionExecutionResult(false, "不支持的系统动作：$action")
        }
    }

    private fun dialContact(context: Context, targetText: String?): ActionExecutionResult {
        val contact = ContactResolver.resolve(context, targetText, CaregiverSupportStore(context))
            ?: return ActionExecutionResult(
                false,
                "未找到联系人：${targetText.orEmpty()}",
                suggestions = listOf("先在家人协助中配置手机号", "或改说完整手机号"),
            )
        val intent = Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(contact.phoneNumber)}".toUri())
        return launchSimpleIntent(context, intent, "已准备拨号：${contact.displayName}")
    }

    private fun sendSms(context: Context, targetText: String?, inputText: String?): ActionExecutionResult {
        val body = inputText?.trim().orEmpty()
        if (body.isBlank()) return ActionExecutionResult(false, "短信内容为空")
        val contact = ContactResolver.resolve(context, targetText, CaregiverSupportStore(context))
        val to = contact?.phoneNumber.orEmpty()
        val uri = if (to.isBlank()) "smsto:".toUri() else "smsto:${Uri.encode(to)}".toUri()
        val intent = Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", body)
        val summary = if (contact != null) "已打开短信发送页：${contact.displayName}" else "已打开短信发送页"
        return launchSimpleIntent(context, intent, summary)
    }

    private fun setAlarm(context: Context, targetText: String?, inputText: String?): ActionExecutionResult {
        val timeText = targetText?.trim().orEmpty().ifBlank { "07:30" }
        val hm = parseHourMinute(timeText)
            ?: return ActionExecutionResult(false, "闹钟时间格式不正确：$timeText", suggestions = listOf("示例：7:30 或 19:45"))
        val label = inputText?.trim().orEmpty().ifBlank { "JoyForOld 提醒" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hm.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, hm.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launchSimpleIntent(context, intent, "已打开闹钟设置：${hm.first}:${hm.second.toString().padStart(2, '0')}")
    }

    private fun addCalendarEvent(context: Context, targetText: String?, inputText: String?): ActionExecutionResult {
        val title = targetText?.trim().orEmpty().ifBlank { "日程提醒" }
        val desc = inputText?.trim().orEmpty()
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, desc)
        return launchSimpleIntent(context, intent, "已打开日历新建事件")
    }

    private fun openWeather(context: Context): ActionExecutionResult {
        val weatherIntent = Intent(Intent.ACTION_VIEW, "weather://".toUri())
        val direct = launchSimpleIntent(context, weatherIntent, "已尝试打开天气应用")
        if (direct.success) return direct
        return openByAlias(context, "天气", "已尝试打开天气入口应用")
    }

    private fun askFamilyForHelp(context: Context, targetText: String?): ActionExecutionResult {
        val store = CaregiverSupportStore(context)
        store.ensureSeededDefaults()
        val preferred = targetText?.trim().orEmpty().ifBlank { "紧急联系人" }
        val contact = store.findContact(preferred) ?: store.loadFamilyContacts().firstOrNull()
            ?: return ActionExecutionResult(false, "家人联系人未配置")
        if (contact.phoneNumber.isBlank()) {
            return ActionExecutionResult(
                false,
                "家人联系人 ${contact.alias} 尚未配置手机号",
                suggestions = listOf("先在家人协助中补全手机号"),
            )
        }
        val message = store.loadEmergencyMessage()
        return sendSms(context, contact.alias, message)
    }

    private fun emergencyHelp(context: Context): ActionExecutionResult {
        val store = CaregiverSupportStore(context).also { it.ensureSeededDefaults() }
        val contact = store.findContact("紧急联系人") ?: store.loadFamilyContacts().firstOrNull()
        if (contact == null || contact.phoneNumber.isBlank()) {
            return ActionExecutionResult(false, "紧急联系人未配置手机号")
        }
        val dial = dialContact(context, contact.alias)
        if (!dial.success) return dial
        sendSms(context, contact.alias, store.loadEmergencyMessage())
        return ActionExecutionResult(true, "已发起紧急呼救：拨号并准备短信")
    }

    private fun readUnreadMessages(context: Context): ActionExecutionResult {
        return ActionExecutionResult(
            success = false,
            summary = "系统通知朗读暂未授权",
            detail = "需额外接入 NotificationListenerService 后才能读取未读通知。",
            suggestions = listOf("先开启系统通知监听能力", "短期可改为打开微信/短信首页并让 Agent 读取页面"),
        )
    }

    private fun navigateHome(context: Context): ActionExecutionResult {
        val store = CaregiverSupportStore(context)
        val homeAddress = store.loadHomeAddress()
        if (homeAddress.isBlank()) {
            return ActionExecutionResult(
                false,
                "还没有设置家的地址",
                suggestions = listOf("先在家人协助设置里填写家的地址"),
            )
        }
        val encoded = Uri.encode(homeAddress)
        val amapIntent = Intent(
            Intent.ACTION_VIEW,
            "androidamap://route?sourceApplication=JoyForOld&dname=$encoded&dev=0&t=0".toUri(),
        )
        val amapResult = launchSimpleIntent(context, amapIntent, "已尝试打开高德地图并导航回家")
        if (amapResult.success) return amapResult

        val geoIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri())
        return launchSimpleIntent(context, geoIntent, "已尝试打开地图并搜索家的地址")
    }

    private fun openByAlias(context: Context, alias: String, successSummary: String): ActionExecutionResult {
        val pkg = InstalledAppResolver.resolvePackage(context, alias)
            ?: return ActionExecutionResult(false, "未找到应用：$alias")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionExecutionResult(false, "应用无法启动：$alias")
        return launchSimpleIntent(context, launch, successSummary)
    }

    private fun launchSimpleIntent(context: Context, intent: Intent, successSummary: String): ActionExecutionResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionExecutionResult(true, successSummary)
        } catch (e: ActivityNotFoundException) {
            ActionExecutionResult(false, "系统未找到可处理该操作的应用", detail = e.message.orEmpty())
        } catch (e: Exception) {
            ActionExecutionResult(false, "系统操作失败", detail = e.message.orEmpty())
        }
    }

    private fun parseHourMinute(text: String): Pair<Int, Int>? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
