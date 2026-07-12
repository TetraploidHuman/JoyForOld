package com.tetraploid.joyforold.agent

/**
 * 过滤系统壳层窗口（状态栏、启动动画、桌面），避免误当成目标应用页面。
 */
object ExternalWindowFilter {
    private val ignoredPackages = setOf(
        "android",
        "com.android.systemui",
        "com.tetraploid.joyforold",
    )

    private val ignoredPackagePrefixes = listOf(
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.sec.android.launcher",
        "com.ss.squarehome",
        // 第三方输入法窗口（键盘弹出时勿当成目标 App 页面）
        "com.google.android.inputmethod",
        "com.android.inputmethod",
        "com.sohu.inputmethod",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.microsoft.swiftkey",
        "com.tencent.qqpinyin",
        "com.huawei.ohos.inputmethod",
    )

    private val systemChromeMarkers = listOf(
        "status_bar_launch_animation_container",
        "status_bar_container",
        "status_bar_start_side_container",
        "notification_icon_area",
    )

    fun isIgnoredPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return true
        if (pkg in ignoredPackages) return true
        return ignoredPackagePrefixes.any { prefix -> pkg.startsWith(prefix) }
    }

    fun isSystemChromeSnapshot(snapshot: StructuredPageSnapshot): Boolean {
        if (isIgnoredPackage(snapshot.packageName)) return true
        if (snapshot.clickables.isNotEmpty() || snapshot.editables.isNotEmpty()) return false
        val corpus = buildString {
            append(snapshot.fingerprint)
            append('|')
            append(snapshot.visibleTexts.joinToString("|"))
            append('|')
            append(snapshot.appHint)
        }
        if (systemChromeMarkers.any { marker -> corpus.contains(marker, ignoreCase = true) }) {
            return true
        }
        if (snapshot.sendButtons.isNotEmpty()) return false
        val onlyStatusTexts = snapshot.visibleTexts.isNotEmpty() &&
            snapshot.visibleTexts.all { text -> looksLikeStatusChromeText(text) }
        return onlyStatusTexts
    }

    fun isUsableSnapshot(snapshot: StructuredPageSnapshot): Boolean =
        !isSystemChromeSnapshot(snapshot)

    fun isSystemChromeTreeSnippet(detail: String): Boolean =
        systemChromeMarkers.any { marker -> detail.contains(marker, ignoreCase = true) }

    private fun looksLikeStatusChromeText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.matches(Regex("\\d{1,2}:\\d{2}"))) return true
        if (trimmed.contains("通知")) return true
        if (trimmed.contains("notification", ignoreCase = true)) return true
        if (trimmed.contains("Android 系统")) return true
        return false
    }
}
