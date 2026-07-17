package com.tetraploid.joyforold.accessibility

/**
 * 微信等 App 白名单无障碍服务类名（AutoJs6 #463）。
 * 仅类名需匹配；应用包名仍为 JoyForOld。
 */
object AccessibilityWhitelist {
    const val SELECT_TO_SPEAK_SERVICE_CLASS =
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun selectToSpeakComponentId(applicationId: String): String =
        "$applicationId/$SELECT_TO_SPEAK_SERVICE_CLASS"
}
