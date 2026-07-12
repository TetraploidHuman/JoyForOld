package com.tetraploid.joyforold.agent

/** 无障碍树常被屏蔽、必须走视觉 tap 的应用 */
object VisionOnlyApps {
    private val packages = setOf(
        AppHintStore.PKG_WECHAT,
        AppHintStore.PKG_QQ,
    )

    fun isVisionOnly(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        return pkg.isNotBlank() && pkg in packages
    }
}
