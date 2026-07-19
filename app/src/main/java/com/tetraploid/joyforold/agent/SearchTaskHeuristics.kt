package com.tetraploid.joyforold.agent

/**
 * 搜索类任务规划提示：当快览里已有搜索框时，引导 Agent 用 click+type 而非盲目 tap。
 *
 * 注意：微信「给某人发消息」不是站内搜索任务；会话列表已有联系人时应直接 click，
 * 禁止被「搜索小程序 搜索栏」等杂项带偏。
 */
object SearchTaskHeuristics {
    private val searchBoxPattern = Regex("""搜索|search|ll_search""", RegexOption.IGNORE_CASE)
    private val keywordPatterns = listOf(
        Regex("""搜索\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
        Regex("""查找\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
        Regex("""搜\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
    )
    private val imContactPatterns = listOf(
        Regex("""微信给(.{1,20}?)(?:发|说)"""),
        Regex("""发微信给(.{1,20}?)(?:说|[:：]|$)"""),
        Regex("""(?:给|跟|和|向)(.{1,20}?)(?:发消息|发信息|发个消息|发送消息|发微信|发[:：]|说)"""),
    )
    private val miniProgramSearchNoise = listOf("搜索小程序", "小程序 搜索", "搜索栏md5")

    fun findSearchBoxLabel(snapshot: StructuredPageSnapshot): String? =
        snapshot.clickables.firstOrNull { label ->
            searchBoxPattern.containsMatchIn(label) && !isMiniProgramSearchNoise(label)
        }

    fun extractSearchKeyword(command: String): String? {
        val trimmed = command.trim()
        for (pattern in keywordPatterns) {
            val match = pattern.find(trimmed) ?: continue
            val keyword = match.groupValues[1].trim()
            if (keyword.length in 1..40) return keyword
        }
        return null
    }

    /** 从「给张三发消息 / 微信给李四说…」里抽出联系人短名。 */
    fun extractImContact(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in imContactPatterns) {
            val match = pattern.find(trimmed) ?: continue
            val name = match.groupValues[1].trim()
                .removePrefix("用微信")
                .removePrefix("微信")
                .trim()
            if (name.length in 1..20 && !name.contains("消息")) return name
        }
        return null
    }

    fun findVisibleContactClickable(
        snapshot: StructuredPageSnapshot,
        contact: String,
    ): String? {
        val needle = contact.trim()
        if (needle.isEmpty()) return null
        return snapshot.clickables.firstOrNull { label ->
            label.contains(needle) && !isSearchLikeLabel(label)
        }
    }

    fun isSearchLikeLabel(label: String): Boolean =
        searchBoxPattern.containsMatchIn(label) || isMiniProgramSearchNoise(label)

    fun isMiniProgramSearchNoise(label: String): Boolean =
        miniProgramSearchNoise.any { label.contains(it) } ||
            (label.contains("搜索") && label.contains("小程序"))

    fun plannerSupplement(command: String, snapshot: StructuredPageSnapshot): String {
        val contact = extractImContact(command)
        if (contact != null) {
            val hit = findVisibleContactClickable(snapshot, contact)
            return if (hit != null) {
                "【联系人提示】会话/列表可点击已含「$hit」，请直接 click 该行进入聊天，" +
                    "禁止点「搜索」「搜索小程序」「搜索栏」。"
            } else {
                "【联系人提示】先在会话列表找「$contact」并 click；看不见再 scroll_down；" +
                    "仍没有才用顶部「搜索」（禁止点「搜索小程序」）。"
            }
        }

        if (!VisionTaskHint.commandLikelyNeedsTextEntry(command)) return ""
        // 「发消息」会命中 textEntry，但上面已处理 IM；此处仅保留真正的搜索类指令
        if (extractSearchKeyword(command).isNullOrBlank() &&
            !command.contains("搜索") && !command.contains("查找") && !command.contains("搜 ")
        ) {
            return ""
        }
        val searchBox = findSearchBoxLabel(snapshot) ?: return ""
        val keyword = extractSearchKeyword(command)
        return buildString {
            append("【搜索提示】可点击项含「$searchBox」，请 click 该搜索框聚焦")
            if (!keyword.isNullOrBlank()) {
                append("，下一步 type 输入「$keyword」")
            } else {
                append("，下一步 type 输入关键词")
            }
            append("，勿盲目 tap 坐标。")
        }
    }

    fun postStepNudge(
        command: String,
        steps: List<AgentStepRecord>,
        snapshot: StructuredPageSnapshot?,
        lastAction: AgentAction,
    ): String? {
        val contact = extractImContact(command)
        if (contact != null) {
            val hit = snapshot?.let { findVisibleContactClickable(it, contact) }
            if (hit != null && lastAction.action.equals("click", ignoreCase = true) &&
                isSearchLikeLabel(lastAction.targetText.orEmpty())
            ) {
                return "【联系人提醒】不要点搜索。请直接 click「$hit」。"
            }
            return null
        }

        if (!VisionTaskHint.commandLikelyNeedsTextEntry(command)) return null
        val searchBox = snapshot?.let(::findSearchBoxLabel) ?: return null
        val types = steps.count {
            it.action.action.equals("type", ignoreCase = true) && it.result.success
        }
        if (types > 0) return null

        val taps = steps.count {
            it.action.action.equals("tap", ignoreCase = true) && it.result.success
        }
        val clicks = steps.count {
            it.action.action.equals("click", ignoreCase = true) && it.result.success
        }

        if (lastAction.action.equals("click", ignoreCase = true) &&
            searchBoxPattern.containsMatchIn(lastAction.targetText.orEmpty())
        ) {
            val keyword = extractSearchKeyword(command)
            return if (!keyword.isNullOrBlank()) {
                "【搜索提醒】已点击搜索框，下一步请 type 输入「$keyword」。"
            } else {
                "【搜索提醒】已点击搜索框，下一步请 type 输入关键词。"
            }
        }

        if (lastAction.action.equals("tap", ignoreCase = true) && taps >= 2 && clicks == 0) {
            val keyword = extractSearchKeyword(command)
            return buildString {
                append("【搜索提醒】指令需要搜索/输入，快览含「$searchBox」。请 click 搜索框")
                if (!keyword.isNullOrBlank()) {
                    append("再 type「$keyword」")
                } else {
                    append("再 type 关键词")
                }
                append("，勿继续随机 tap。")
            }
        }
        return null
    }
}
