package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.preset.PresetTextNormalizer

/**
 * 离线本地系统快捷指令：设置页、打开应用等，不依赖网络与 AI。
 */
object LocalSystemShortcutResolver {
    private const val ROUTE_CONFIDENCE = 0.96

    data class Match(
        val steps: List<AgentAction>,
        val confidence: Double = ROUTE_CONFIDENCE,
    )

    private data class SettingsShortcut(
        val action: String,
        val phrases: Set<String>,
        val summary: String,
    )

    private val SETTINGS_SHORTCUTS = listOf(
        SettingsShortcut(
            action = "open_wifi_settings",
            phrases = setOf(
                "打开wifi", "打开无线网", "打开无线网络", "开wifi", "wifi设置", "无线网设置",
                "wlan设置", "连wifi", "连接wifi",
            ),
            summary = "已打开无线网络设置",
        ),
        SettingsShortcut(
            action = "open_bluetooth_settings",
            phrases = setOf(
                "打开蓝牙", "开蓝牙", "蓝牙设置", "连接蓝牙", "连蓝牙",
            ),
            summary = "已打开蓝牙设置",
        ),
        SettingsShortcut(
            action = "open_sound_settings",
            phrases = setOf(
                "打开声音", "声音设置", "音量设置", "调音量", "音量调节", "媒体音量",
            ),
            summary = "已打开声音设置",
        ),
        SettingsShortcut(
            action = "open_mobile_data_settings",
            phrases = setOf(
                "打开移动数据", "移动数据设置", "流量设置", "数据流量", "蜂窝数据", "上网设置",
            ),
            summary = "已打开移动数据设置",
        ),
        SettingsShortcut(
            action = "open_location_settings",
            phrases = setOf(
                "打开定位", "定位设置", "位置设置", "gps设置", "打开gps",
            ),
            summary = "已打开定位设置",
        ),
        SettingsShortcut(
            action = "open_display_settings",
            phrases = setOf(
                "打开显示设置", "显示设置", "屏幕设置", "亮度设置",
            ),
            summary = "已打开显示设置",
        ),
        SettingsShortcut(
            action = "open_settings",
            phrases = setOf(
                "打开设置", "系统设置", "手机设置", "进入设置",
            ),
            summary = "已打开系统设置",
        ),
    )

    private val OPEN_APP_PATTERN = Regex("""^(?:打开|启动|运行|进入)\s*(.+)$""")

    fun match(command: String, context: Context? = null): Match? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null

        matchSettingsShortcut(trimmed)?.let { return it }

        if (context != null) {
            matchOpenApp(trimmed, context)?.let { return it }
        }

        return null
    }

    internal fun matchSettingsShortcut(command: String): Match? {
        val normalized = normalize(command)
        if (normalized.isBlank()) return null

        val hit = SETTINGS_SHORTCUTS.firstOrNull { shortcut ->
            shortcut.phrases.any { phrase -> normalized == phrase || normalized.contains(phrase) }
        } ?: return null

        return Match(
            steps = listOf(
                AgentAction(action = hit.action),
                AgentAction(action = "finish", message = hit.summary, finished = true),
            ),
        )
    }

    internal fun matchOpenApp(command: String, context: Context): Match? {
        val appQuery = OPEN_APP_PATTERN.find(command.trim())?.groupValues?.get(1)?.trim().orEmpty()
        if (appQuery.isBlank()) return null
        if (isReservedSettingsPhrase(normalize(appQuery))) return null

        val packageName = InstalledAppResolver.resolvePackage(context, appQuery) ?: return null
        val label = InstalledAppResolver.getLaunchableApps(context)
            .firstOrNull { it.packageName == packageName }
            ?.label
            ?: appQuery

        return Match(
            steps = listOf(
                AgentAction(action = "open_app", targetText = label),
                AgentAction(action = "finish", message = "已打开：$label", finished = true),
            ),
        )
    }

    private fun isReservedSettingsPhrase(normalized: String): Boolean {
        return SETTINGS_SHORTCUTS.any { shortcut ->
            shortcut.phrases.any { phrase -> normalized == phrase || normalized.contains(phrase) }
        }
    }

    private fun normalize(text: String): String {
        return PresetTextNormalizer.normalize(text)
            .lowercase()
            .replace(Regex("\\s+"), "")
    }
}
