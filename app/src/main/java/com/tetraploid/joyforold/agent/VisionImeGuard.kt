package com.tetraploid.joyforold.agent

import android.graphics.Rect

/**
 * 判断视觉模式下的 tap/send 坐标是否落在输入法窗口内（与具体 App/键盘品牌无关）。
 */
object VisionImeGuard {
    fun normalizedTapHitsIme(
        xNorm: Int,
        yNorm: Int,
        imeBounds: Rect?,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (imeBounds == null || screenWidth <= 0 || screenHeight <= 0) return false
        val x = xNorm / 1000f * screenWidth
        val y = yNorm / 1000f * screenHeight
        return imeBounds.contains(x.toInt(), y.toInt())
    }
}
