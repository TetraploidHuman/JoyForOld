package com.tetraploid.joyforold.agent

/**
 * 步骤后建议：仅反馈可客观观测的状态（页面是否变化、操作是否重复失败），不解析用户意图。
 */
object AgentStepAdvisor {
    fun postStepHint(
        session: AgentConversationSession,
        action: AgentAction,
        result: ActionExecutionResult,
        snapshot: StructuredPageSnapshot?,
        rootCommand: String,
        pageDiff: String = "",
        visionMode: Boolean = false,
    ): String? {
        MediaPlaybackHeuristics.plannerHint(session, snapshot, rootCommand)?.let { return it }

        SearchTaskHeuristics.postStepNudge(
            command = rootCommand,
            steps = session.stepRecords,
            snapshot = snapshot,
            lastAction = action,
        )?.let { return it }

        VisionTaskHint.postStepNudge(
            command = rootCommand,
            steps = session.stepRecords,
            visionMode = visionMode,
            lastAction = action,
        )?.let { return it }

        if (!result.success) {
            return when (action.action.lowercase()) {
                "find_on_page" -> hintAfterFailedFind(session, action, visionMode)
                "click" -> hintAfterFailedPlaybackClick(action)
                else -> null
            }
        }
        return hintAfterSuccessfulAction(action, pageDiff, visionMode)
    }

    private fun hintAfterSuccessfulAction(
        action: AgentAction,
        pageDiff: String,
        visionMode: Boolean,
    ): String? {
        if (!action.action.equals("click", ignoreCase = true) &&
            !action.action.equals("type", ignoreCase = true) &&
            !action.action.equals("tap", ignoreCase = true)
        ) {
            return null
        }
        if (!AgentActionGuard.pageDiffIndicatesNoChange(pageDiff)) return null
        val target = action.targetText?.trim().orEmpty()
        val label = if (target.isNotBlank()) "「$target」" else action.action
        val strategyHint = if (visionMode || action.action.equals("tap", ignoreCase = true)) {
            "请换其他 tap 坐标或 finish+waiting_for_user 询问用户。"
        } else {
            "请 read_tree 查看可点击项并换策略，勿重复相同操作。"
        }
        return "【页面反馈】执行 $label 后【页面变化】无明显变化，该操作可能未推进目标。" +
            strategyHint
    }

    private fun hintAfterFailedPlaybackClick(action: AgentAction): String? {
        if (!MediaPlaybackHeuristics.isAbstractPlaybackTarget(action.targetText)) return null
        return "【步骤建议】无障碍树中通常没有「${action.targetText}」。若已在视频详情页且标题匹配，请直接 finish，勿重复 read_tree。"
    }

    private fun hintAfterFailedFind(
        session: AgentConversationSession,
        action: AgentAction,
        visionMode: Boolean,
    ): String? {
        val query = action.targetText?.trim().orEmpty()
        if (query.length < 2) return null
        val failedSameQuery = session.stepRecords.takeLast(8).count { step ->
            !step.result.success &&
                step.action.action.equals("find_on_page", ignoreCase = true) &&
                step.action.targetText?.trim().equals(query, ignoreCase = true)
        }
        if (failedSameQuery < 1) return null
        return if (visionMode) {
            "【步骤建议】当前应用无障碍树不可用，无法在页面中搜索「$query」。请根据截图用 tap 重新规划，勿重复 find_on_page。"
        } else {
            "【步骤建议】当前可见页面未出现「$query」。请结合页面快览或 read_tree 重新规划下一步，勿重复 find_on_page。"
        }
    }
}
