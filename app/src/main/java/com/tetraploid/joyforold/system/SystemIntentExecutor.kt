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
import com.tetraploid.joyforold.core.AssistSessionStarters
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.network.JoyHttpClients
import com.tetraploid.joyforold.network.getText
import com.tetraploid.joyforold.util.NetworkStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object SystemIntentExecutor {
    private const val DEFAULT_EVENT_DURATION_MS = 60L * 60L * 1000L

    private val httpClient: HttpClient = JoyHttpClients.quick()
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
            "open_font_settings", "open_display_settings" -> openDisplaySettings(context)
            "open_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_SETTINGS), "已打开系统设置")
            "open_wifi_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_WIFI_SETTINGS), "已打开无线网络设置")
            "open_bluetooth_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "已打开蓝牙设置")
            "open_sound_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_SOUND_SETTINGS), "已打开声音设置")
            "open_mobile_data_settings" -> openMobileDataSettings(context)
            "open_location_settings" -> launchSimpleIntent(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "已打开定位设置")
            "open_app" -> openApp(context, targetText)
            "navigate_home" -> navigateHome(context)
            "navigate_to" -> navigateTo(context, targetText, inputText)
            "navigate_pick" -> ActionExecutionResult(
                false,
                "地点选择应由编排层处理",
                detail = "navigate_pick",
            )
            "read_unread_messages" -> readUnreadMessages(context)
            "tell_time" -> tellTime()
            "query_weather" -> queryWeather(context, targetText)
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

    private fun openDisplaySettings(context: Context): ActionExecutionResult {
        return launchSimpleIntent(context, Intent(Settings.ACTION_DISPLAY_SETTINGS), "已打开显示设置")
    }

    private fun openMobileDataSettings(context: Context): ActionExecutionResult {
        val intents = listOf(
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            val result = launchSimpleIntent(context, intent, "已打开移动数据设置")
            if (result.success) return result
        }
        return ActionExecutionResult(false, "暂时无法打开移动数据设置")
    }

    private fun openApp(context: Context, targetText: String?): ActionExecutionResult {
        val query = targetText?.trim().orEmpty()
        if (query.isEmpty()) {
            return ActionExecutionResult(false, "打开失败", detail = "缺少应用名称")
        }

        val packageName = InstalledAppResolver.resolvePackage(context, query)
            ?: return ActionExecutionResult(
                success = false,
                summary = "未识别应用：$query",
                detail = buildString {
                    val suggestions = InstalledAppResolver.suggestMatches(context, query, limit = 6)
                        .joinToString("、") { it.label }
                    append("请说已安装应用的中文名称。")
                    if (suggestions.isNotBlank()) append("\n你可能想找：$suggestions")
                },
                suggestions = listOf("换用桌面图标上的应用名称重试"),
            )

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionExecutionResult(
                success = false,
                summary = "无法打开：$query",
                detail = "应用 $packageName 没有桌面启动入口",
            )

        return launchSimpleIntent(context, launchIntent, "已打开：$query")
    }

    private fun addCalendarEvent(context: Context, targetText: String?, inputText: String?): ActionExecutionResult {
        val title = targetText?.trim().orEmpty().ifBlank { "日程提醒" }
        val encoded = parseCalendarInput(inputText)
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, encoded.notes)
        encoded.beginMs?.let { begin ->
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + DEFAULT_EVENT_DURATION_MS)
        }
        val summary = if (encoded.beginMs != null) {
            "已打开日历新建事件：$title（时间已预填）"
        } else {
            "已打开日历新建事件"
        }
        return launchSimpleIntent(context, intent, summary)
    }

    private data class CalendarInput(
        val notes: String,
        val beginMs: Long? = null,
    )

    private fun parseCalendarInput(inputText: String?): CalendarInput {
        val text = inputText?.trim().orEmpty()
        val marker = "@t="
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return CalendarInput(notes = text)
        val isoPart = text.substring(markerIndex + marker.length)
        val separatorIndex = isoPart.indexOf('|')
        val iso = if (separatorIndex >= 0) isoPart.substring(0, separatorIndex).trim() else isoPart.trim()
        val notes = if (separatorIndex >= 0) isoPart.substring(separatorIndex + 1).trim() else ""
        return CalendarInput(notes = notes, beginMs = parseEventBeginMs(iso))
    }

    private fun parseEventBeginMs(iso: String): Long? {
        if (iso.isBlank()) return null
        return runCatching {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun openWeather(context: Context): ActionExecutionResult {
        val weatherIntent = Intent(Intent.ACTION_VIEW, "weather://".toUri())
        val direct = launchSimpleIntent(context, weatherIntent, "已尝试打开天气应用")
        if (direct.success) return direct
        return openByAlias(context, "天气", "已尝试打开天气入口应用")
    }

    private fun askFamilyForHelp(context: Context, targetText: String?): ActionExecutionResult {
        AssistSessionStarters.delegate.startElderAssistSession()
        return ActionExecutionResult(
            success = true,
            summary = "已发起远程协助，请到「协作」页查看协助码并告诉家人",
            suggestions = listOf("家人在协作页输入协助码即可连接"),
        )
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
        if (!NotificationAccessPermission.isEnabled(context)) {
            return ActionExecutionResult(
                success = false,
                summary = "通知朗读未授权",
                detail = "请先在应用设置中开启「通知使用权」，才能读取未读消息。",
                suggestions = listOf("打开 JoyForOld 设置页，开启通知使用权"),
            )
        }
        val recent = UnreadNotificationStore.recent(limit = 5)
        if (recent.isEmpty()) {
            return ActionExecutionResult(
                success = true,
                summary = "目前没有新的未读通知",
                detail = "若刚收到消息，请稍等几秒后重试。",
            )
        }
        val spoken = recent.joinToString(separator = "。") { entry ->
            val body = entry.text.ifBlank { entry.title }
            val prefix = if (entry.title.isNotBlank() && entry.title != body) {
                "${entry.appLabel}，${entry.title}，$body"
            } else {
                "${entry.appLabel}，$body"
            }
            prefix.trim()
        }
        return ActionExecutionResult(
            success = true,
            summary = "最近未读通知：$spoken",
            detail = spoken,
        )
    }

    private fun tellTime(): ActionExecutionResult {
        val spoken = TimeFormatter.spokenNow()
        return ActionExecutionResult(
            success = true,
            summary = spoken,
            detail = spoken,
        )
    }

    private fun queryWeather(context: Context, targetText: String?): ActionExecutionResult {
        if (NetworkStatus.offlineHint(context) != null) {
            val fallback = openWeather(context)
            return if (fallback.success) {
                ActionExecutionResult(
                    success = true,
                    summary = "网络不可用，已为您打开天气应用",
                    detail = fallback.summary,
                )
            } else {
                ActionExecutionResult(false, "网络不可用，暂时无法查询天气")
            }
        }
        val city = targetText?.trim().orEmpty().ifBlank { defaultWeatherCity(context) }
        val encoded = URLEncoder.encode(city, StandardCharsets.UTF_8.name())
        return try {
            val body = runBlocking {
                httpClient.getText(
                    url = "https://wttr.in/$encoded?format=%l:+%c+%t+%h&lang=zh",
                    userAgent = "JoyForOld/1.0",
                )
            }.trim()
            if (body.isBlank()) {
                return queryWeatherFallback(context, city, "暂时没有查到天气信息")
            }
            val spoken = if (body.contains(city)) body else "$city，$body"
            ActionExecutionResult(
                success = true,
                summary = spoken,
                detail = spoken,
            )
        } catch (_: Exception) {
            queryWeatherFallback(context, city, "查询天气失败")
        }
    }

    private fun queryWeatherFallback(
        context: Context,
        city: String,
        reason: String,
    ): ActionExecutionResult {
        val fallback = openWeather(context)
        return if (fallback.success) {
            ActionExecutionResult(
                success = true,
                summary = "$reason，已为您打开天气应用",
                detail = fallback.summary,
            )
        } else {
            ActionExecutionResult(false, reason)
        }
    }

    private fun defaultWeatherCity(context: Context): String {
        val home = CaregiverSupportStore(context).loadHomeAddress().trim()
        if (home.isNotBlank()) {
            home.split(Regex("""[省市区县\s]"""))
                .firstOrNull { it.length >= 2 }
                ?.let { return it }
        }
        return "北京"
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
        return navigateToDestination(context, homeAddress, successLabel = "家")
    }

    /**
     * 用地图深链直接发起到目的地的路线规划，避免 open_app 后落在上次搜索页、
     * 或在 POI 列表里搜完就误判任务完成。
     */
    private fun navigateTo(context: Context, targetText: String?, inputText: String?): ActionExecutionResult {
        val destination = targetText?.trim().orEmpty()
        if (destination.isBlank()) {
            return ActionExecutionResult(
                false,
                "未指定目的地",
                suggestions = listOf("请说明要去哪里，例如「附近的肯德基」"),
            )
        }
        val landmark = inputText?.trim().orEmpty()
        val label = when {
            landmark.isBlank() -> destination
            AmapPoiResolver.looksLikeAdminRegion(landmark) -> "${landmark}的$destination"
            else -> "${landmark}附近的$destination"
        }
        return navigateToDestination(
            context,
            destination,
            successLabel = label,
            nearLandmark = landmark.ifBlank { null },
        )
    }

    private fun navigateToDestination(
        context: Context,
        destination: String,
        successLabel: String,
        nearLandmark: String? = null,
    ): ActionExecutionResult {
        // 地标/行政区：先解析锚点，再周边搜品类/品牌（不用设备当前位置）
        if (!nearLandmark.isNullOrBlank()) {
            val nearHits = AmapPoiResolver.searchNearLandmark(context, nearLandmark, destination, limit = 1)
            nearHits.firstOrNull()?.let { return navigateToPoi(context, it) }
        }

        // 目的地字符串里自带「行政区的品类」时也要拆开，避免整串按当前位置 around
        val scoped = com.tetraploid.joyforold.agent.SystemIntentLocalParser.splitScopedPoiQuery(destination)
        if (scoped != null && nearLandmark.isNullOrBlank()) {
            val nearHits = AmapPoiResolver.searchNearLandmark(context, scoped.landmark, scoped.poi, limit = 1)
            nearHits.firstOrNull()?.let { return navigateToPoi(context, it) }
        }

        val effectiveKeyword = scoped?.poi ?: destination
        val effectiveScope = nearLandmark ?: scoped?.landmark

        // 1) 有 Web Key：检索最近/最匹配 POI → navi 直达（无候选列表）
        val resolved = if (effectiveScope.isNullOrBlank() && looksLikeSpecificAddress(effectiveKeyword)) {
            AmapPoiResolver.geocodeAddress(
                effectiveKeyword,
                cityHint = AmapPoiResolver.extractCityHintFromPlace(effectiveKeyword) ?: inferCityHint(context),
            ) ?: AmapPoiResolver.resolveNearest(context, effectiveKeyword)
        } else {
            AmapPoiResolver.resolveNearest(context, effectiveKeyword, regionOrLandmark = effectiveScope)
        }
        if (resolved != null) {
            return navigateToPoi(context, resolved)
        }

        val encoded = Uri.encode(if (effectiveScope != null) "$effectiveScope$effectiveKeyword" else effectiveKeyword)
        // 2) 具体地址：路线规划（回家同款）；否则 keywordNavi（会出候选，作降级）
        val amapUri = if (effectiveScope.isNullOrBlank() && looksLikeSpecificAddress(effectiveKeyword)) {
            "androidamap://route?sourceApplication=JoyForOld&dname=$encoded&dev=0&t=0"
        } else {
            "androidamap://keywordNavi?sourceApplication=JoyForOld&keyword=$encoded&style=2"
        }
        val summary = when {
            !AmapPoiResolver.isConfigured() && !looksLikeSpecificAddress(effectiveKeyword) ->
                "已打开高德关键词导航：$successLabel（未配置 amap.web.key，可能出现候选列表）"
            resolved == null && !looksLikeSpecificAddress(effectiveKeyword) ->
                "已打开高德关键词导航：$successLabel（地点解析未命中，可能出现候选列表）"
            else -> "已尝试打开高德地图并导航前往：$successLabel"
        }
        val amapResult = launchSimpleIntent(
            context,
            Intent(Intent.ACTION_VIEW, amapUri.toUri()).apply {
                setPackage("com.autonavi.minimap")
            },
            summary,
        )
        if (amapResult.success) return amapResult

        val baiduIntent = Intent(
            Intent.ACTION_VIEW,
            "baidumap://map/direction?destination=name:$encoded&mode=driving&src=JoyForOld".toUri(),
        )
        val baiduResult = launchSimpleIntent(
            context,
            baiduIntent,
            "已尝试打开百度地图并规划前往：$successLabel",
        )
        if (baiduResult.success) return baiduResult

        val geoIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri())
        return launchSimpleIntent(context, geoIntent, "已尝试打开地图并搜索：$successLabel")
    }

    /** 已知坐标时直达高德导航（候选点选定后调用）。 */
    fun navigateToPoi(context: Context, poi: AmapPoiResolver.Poi): ActionExecutionResult {
        val naviUri =
            "androidamap://navi?sourceApplication=JoyForOld" +
                "&poiname=${Uri.encode(poi.name)}" +
                "&lat=${poi.lat}&lon=${poi.lon}&dev=0&style=2"
        return launchSimpleIntent(
            context,
            Intent(Intent.ACTION_VIEW, naviUri.toUri()).apply {
                setPackage("com.autonavi.minimap")
            },
            "已尝试打开高德地图并导航前往：${poi.name}",
        )
    }

    private fun inferCityHint(context: Context): String? {
        val home = CaregiverSupportStore(context).loadHomeAddress().trim()
        if (home.isBlank()) return null
        return Regex("""([\u4e00-\u9fa5]{2,10}(?:市|县|州|区))""").find(home)?.groupValues?.get(1)
    }

    /** 含路名门牌等更像具体地址；学校名/品牌等走 POI 检索。 */
    private fun looksLikeSpecificAddress(destination: String): Boolean {
        if (destination.length < 6) return false
        if (com.tetraploid.joyforold.agent.SystemIntentLocalParser.splitScopedPoiQuery(destination) != null) {
            return false
        }
        return destination.contains(Regex("""[路街道巷弄号栋楼区县镇村市省]"""))
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
