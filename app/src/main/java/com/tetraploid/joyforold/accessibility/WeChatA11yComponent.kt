package com.tetraploid.joyforold.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

/**
 * 「微信支持」组件：内置于主 APK，无需独立安装。
 * 用户只需在系统无障碍里开启「JoyForOld · 微信支持组件」。
 */
object WeChatA11yComponent {
    const val DISPLAY_NAME = "微信支持"

    enum class Status {
        /** 系统无障碍尚未开启该组件服务 */
        OFF,

        /** 已在设置勾选，进程尚未连上 */
        PENDING,

        /** 已连接，主服务读树会走白名单类名 */
        ACTIVE,
    }

    fun status(context: Context): Status {
        val enabled = AccessibilityPermission.isWhitelistReaderSettingEnabled(context)
        val connected = AccessibilityPermission.isWhitelistReaderConnected()
        return when {
            connected -> Status.ACTIVE
            enabled -> Status.PENDING
            else -> Status.OFF
        }
    }

    /** 主服务是否应优先用本组件读 UI 树 */
    fun isTreeReaderReady(): Boolean = SelectToSpeakService.instance != null

    fun openAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun statusLabel(status: Status): String = when (status) {
        Status.OFF -> "未启用"
        Status.PENDING -> "开启中…"
        Status.ACTIVE -> "已启用"
    }

    fun statusHint(status: Status): String = when (status) {
        Status.OFF ->
            "内置于 JoyForOld。请在系统无障碍中开启「JoyForOld · 微信支持组件」，与主服务一起使用。"
        Status.PENDING ->
            "已在系统中勾选，等待服务连接。若长时间未就绪，可关闭后再重新开启一次。"
        Status.ACTIVE ->
            "已就绪：读取微信等界面时会使用白名单无障碍通道；点击与输入仍由 JoyForOld 主服务完成。"
    }
}
