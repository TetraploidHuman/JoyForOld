package com.tetraploid.joyforold.agent

object ElderTaskTemplateMatcher {
    fun match(command: String): List<AgentAction>? {
        val text = command.trim()
        if (text.isBlank()) return null

        if (containsAny(text, "给女儿打电话", "打电话给女儿")) {
            return listOf(
                AgentAction(action = "dial_contact", targetText = "女儿"),
                AgentAction(action = "finish", message = "已为您准备给女儿打电话，请确认是否拨出。", waitingForUser = true),
            )
        }
        if (containsAny(text, "给儿子发微信", "给儿子发消息")) {
            return listOf(
                AgentAction(action = "open_app", targetText = "微信"),
                AgentAction(action = "finish", message = "已打开微信，接下来我会帮您给儿子发消息。", finished = true),
            )
        }
        if (containsAny(text, "接听视频通话", "接视频电话")) {
            return listOf(
                AgentAction(action = "open_app", targetText = "微信"),
                AgentAction(action = "finish", message = "已打开通话应用，请告诉我来电联系人。", waitingForUser = true),
            )
        }
        if (containsAny(text, "播放戏曲", "听戏曲", "播放新闻", "听新闻")) {
            return listOf(
                AgentAction(action = "open_app", targetText = "喜马拉雅"),
                AgentAction(action = "open_app", targetText = "今日头条"),
                AgentAction(action = "finish", message = "已尝试打开内容应用，请说要播放的具体内容。", waitingForUser = true),
            )
        }
        if (containsAny(text, "看天气")) {
            return listOf(
                AgentAction(action = "open_weather"),
                AgentAction(action = "finish", message = "已为您打开天气。", finished = true),
            )
        }
        if (containsAny(text, "我要回家", "回家", "导航回家")) {
            return listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "正在为您导航回家。", finished = true),
            )
        }
        if (containsAny(text, "打开健康码")) {
            return listOf(
                AgentAction(action = "open_health_code"),
                AgentAction(action = "finish", message = "已尝试打开健康码入口。", finished = true),
            )
        }
        if (containsAny(text, "打开付款码", "打开收款码")) {
            return listOf(
                AgentAction(action = "open_payment_code"),
                AgentAction(action = "finish", message = "已尝试打开付款码入口。", finished = true),
            )
        }
        if (containsAny(text, "叫家人帮忙", "联系家人帮忙")) {
            return listOf(
                AgentAction(action = "ask_family_for_help"),
                AgentAction(action = "finish", message = "已准备向家人发求助短信。", finished = true),
            )
        }
        if (containsAny(text, "紧急呼救", "sos", "救命")) {
            return listOf(
                AgentAction(action = "emergency_help"),
                AgentAction(action = "finish", message = "已执行紧急呼救流程。", finished = true),
            )
        }
        if (containsAny(text, "放大字体", "字体调大", "字太小")) {
            return listOf(
                AgentAction(action = "open_font_settings"),
                AgentAction(action = "finish", message = "已打开字体显示设置。", finished = true),
            )
        }
        if (containsAny(text, "读未读消息", "读通知", "读一下消息")) {
            return listOf(
                AgentAction(action = "read_unread_messages"),
                AgentAction(action = "finish", message = "我先尝试读取未读消息。", finished = true),
            )
        }
        return null
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
