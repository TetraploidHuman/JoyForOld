package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.preset.PresetTextNormalizer

object ElderTaskTemplateMatcher {
    fun match(command: String): List<AgentAction>? {
        val normalized = PresetTextNormalizer.normalize(command)
        if (normalized.isBlank()) return null
        return TEMPLATES.firstOrNull { template ->
            template.aliases.any { PresetTextNormalizer.normalize(it) == normalized }
        }?.actions
    }

    private data class TemplateIntent(
        val aliases: List<String>,
        val actions: List<AgentAction>,
    )

    private val TEMPLATES = listOf(
        TemplateIntent(
            aliases = listOf("给女儿打电话", "打电话给女儿"),
            actions = listOf(
                AgentAction(action = "dial_contact", targetText = "女儿"),
                AgentAction(action = "finish", message = "已为您准备给女儿打电话，请确认是否拨出。", waitingForUser = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("给儿子发微信"),
            actions = listOf(
                AgentAction(action = "open_app", targetText = "微信"),
                AgentAction(action = "finish", message = "已打开微信，接下来我会帮您给儿子发消息。", finished = true),
            ),
        ),
        // 「给家人发消息」等未点名 App 的说法不再模板写死微信，交给 LLM 选型。
        TemplateIntent(
            aliases = listOf("接听视频通话", "接视频电话"),
            actions = listOf(
                AgentAction(action = "open_app", targetText = "微信"),
                AgentAction(action = "finish", message = "已打开通话应用，请告诉我来电联系人。", waitingForUser = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("播放戏曲", "听戏曲", "播放新闻", "听新闻"),
            actions = listOf(
                AgentAction(action = "open_app", targetText = "喜马拉雅"),
                AgentAction(action = "open_app", targetText = "今日头条"),
                AgentAction(action = "finish", message = "已尝试打开内容应用，请说要播放的具体内容。", waitingForUser = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("看天气", "打开天气"),
            actions = listOf(
                AgentAction(action = "open_weather"),
                AgentAction(action = "finish", message = "已为您打开天气。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("查看天气", "查天气", "今天天气怎么样", "帮我查一下天气"),
            actions = listOf(
                AgentAction(action = "query_weather"),
                AgentAction(action = "finish", message = "正在查询天气", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("查看时间", "几点了", "现在几点", "现在几点钟了", "几点钟了", "报时"),
            actions = listOf(
                AgentAction(action = "tell_time"),
                AgentAction(action = "finish", message = "正在查看时间", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("导航回家"),
            actions = listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "正在为您导航回家。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("打开健康码"),
            actions = listOf(
                AgentAction(action = "open_health_code"),
                AgentAction(action = "finish", message = "已尝试打开健康码入口。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("打开付款码", "打开收款码"),
            actions = listOf(
                AgentAction(action = "open_payment_code"),
                AgentAction(action = "finish", message = "已尝试打开付款码入口。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("叫家人帮忙", "联系家人帮忙"),
            actions = listOf(
                AgentAction(action = "ask_family_for_help"),
                AgentAction(action = "finish", message = "已准备向家人发求助短信。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("紧急呼救", "sos", "救命"),
            actions = listOf(
                AgentAction(action = "emergency_help"),
                AgentAction(action = "finish", message = "已执行紧急呼救流程。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("放大字体", "字体调大", "字太小"),
            actions = listOf(
                AgentAction(action = "open_font_settings"),
                AgentAction(action = "finish", message = "已打开字体显示设置。", finished = true),
            ),
        ),
        TemplateIntent(
            aliases = listOf("读未读消息", "读通知", "读一下消息", "帮我读一下未读消息"),
            actions = listOf(
                AgentAction(action = "read_unread_messages"),
                AgentAction(action = "finish", message = "我先尝试读取未读消息。", finished = true),
            ),
        ),
    )
}
