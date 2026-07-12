package com.tetraploid.joyforold.agent

/**
 * 搜索类任务规划提示：当快览里已有搜索框时，引导 Agent 用 click+type 而非盲目 tap。
 */
object SearchTaskHeuristics {
    private val searchBoxPattern = Regex("""搜索|search|ll_search""", RegexOption.IGNORE_CASE)
    private val keywordPatterns = listOf(
        Regex("""搜索\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
        Regex("""查找\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
        Regex("""搜\s+(.+?)(?:的|相关|最新|动态|新闻|内容|$)"""),
    )

    fun findSearchBoxLabel(snapshot: StructuredPageSnapshot): String? =
        snapshot.clickables.firstOrNull { searchBoxPattern.containsMatchIn(it) }

    fun extractSearchKeyword(command: String): String? {
        val trimmed = command.trim()
        for (pattern in keywordPatterns) {
            val match = pattern.find(trimmed) ?: continue
            val keyword = match.groupValues[1].trim()
            if (keyword.length in 1..40) return keyword
        }
        return null
    }

    fun plannerSupplement(command: String, snapshot: StructuredPageSnapshot): String {
        if (!VisionTaskHint.commandLikelyNeedsTextEntry(command)) return ""
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
