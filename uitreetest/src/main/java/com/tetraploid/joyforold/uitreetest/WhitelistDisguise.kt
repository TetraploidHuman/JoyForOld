package com.tetraploid.joyforold.uitreetest

/**
 * 微信等 App 校验无障碍白名单时，通常只认完整组件 ID 的「类名」后半段。
 * 见 AutoJs6 #463：[SELECT_TO_SPEAK_SERVICE_CLASS]
 */
object WhitelistDisguise {
    const val SELECT_TO_SPEAK_SERVICE_CLASS =
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun enabledServiceComponentId(applicationId: String): String =
        "$applicationId/$SELECT_TO_SPEAK_SERVICE_CLASS"
}
