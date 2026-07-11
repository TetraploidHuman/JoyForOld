package com.tetraploid.joyforold.agent

/**
 * 语音续听时识别用户是否改口发了新任务（非 AI 确认判断，仅作话题切换兜底）。
 */
object VoiceFollowUpDetector {
    private val newCommandMarkers = listOf(
        "打开", "帮我", "给我", "麻烦", "发送", "发给", "打电话", "拨打", "拨给",
        "导航", "返回", "点击", "输入", "滚动", "查看", "播放", "设置", "调亮", "调暗",
    )

    fun looksLikeNewCommand(utterance: String): Boolean {
        val text = utterance.trim()
        if (text.length < 4) return false
        return newCommandMarkers.any { text.contains(it) }
    }
}
