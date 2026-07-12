package com.tetraploid.joyforold.agent

/**
 * 视觉模式下、与具体 App/任务无关的通用规划提示（由用户指令 + 步骤历史推导）。
 */
object VisionTaskHint {
    private val textEntryPattern = Regex(
        """(输入|填写|打字|搜索|查找|发.{0,6}(消息|短信|文字|内容)|说[^什么]|留言|回复)""",
    )

    fun commandLikelyNeedsTextEntry(command: String): Boolean =
        textEntryPattern.containsMatchIn(command.trim())

    fun pageContextSupplement(
        command: String,
        steps: List<AgentStepRecord>,
        visionMode: Boolean,
    ): String {
        if (!visionMode || !commandLikelyNeedsTextEntry(command)) return ""
        val successfulTypes = steps.count {
            it.action.action.equals("type", ignoreCase = true) && it.result.success
        }
        val successfulTaps = steps.count {
            it.action.action.equals("tap", ignoreCase = true) && it.result.success
        }
        if (successfulTypes > 0 || successfulTaps < 1) return ""
        return "【视觉提示】用户指令似乎需要输入文字，但近期只有坐标点击。" +
            "请根据截图定位输入框，先 tap 再 type；若关键信息（联系人/内容）在指令中不清楚，" +
            "用 finish+waiting_for_user 向用户确认，勿猜测。"
    }

    fun postStepNudge(
        command: String,
        steps: List<AgentStepRecord>,
        visionMode: Boolean,
        lastAction: AgentAction,
    ): String? {
        if (!visionMode || !lastAction.action.equals("tap", ignoreCase = true)) return null
        if (!commandLikelyNeedsTextEntry(command)) return null
        val types = steps.count { it.action.action.equals("type", ignoreCase = true) }
        val taps = steps.count {
            it.action.action.equals("tap", ignoreCase = true) && it.result.success
        }
        if (types > 0 || taps < 2) return null
        return "【视觉提醒】任务需要输入文字，不能只用 tap。请根据截图 tap 输入框后 type。"
    }
}
