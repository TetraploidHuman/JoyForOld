package com.tetraploid.joyforold.agent

/**
 * 执行后验证（对齐 DroidLM AgentVerifier）：工具报告成功 ≠ 任务推进。
 * 对比动作前后的页面快照，判断 UI 是否按预期变化。
 */
object AgentVerifier {
    private val uiMutatingActions = setOf(
        "click", "type", "send", "scroll_down", "scroll_up", "swipe_down",
        "back", "home", "open_app",
    )

    fun verify(
        action: AgentAction,
        executionResult: ActionExecutionResult,
        beforeSnapshot: StructuredPageSnapshot?,
        afterSnapshot: StructuredPageSnapshot?,
        pageDiff: String,
    ): AgentVerificationResult {
        if (!executionResult.success) {
            return AgentVerificationResult.failed(
                message = executionResult.summary.ifBlank { "操作执行失败" },
                expected = describe(action),
                actual = executionResult.detail.ifBlank { executionResult.summary },
            )
        }

        val actionName = action.action.lowercase()
        return when {
            actionName == "open_app" -> verifyOpenApp(action, beforeSnapshot, afterSnapshot, pageDiff)
            actionName == "find_on_page" -> verifyFindOnPage(action, executionResult)
            actionName in uiMutatingActions -> verifyUiChanged(action, beforeSnapshot, afterSnapshot, pageDiff)
            else -> AgentVerificationResult.notApplicable("无需验证：${action.action}")
        }
    }

    private fun verifyOpenApp(
        action: AgentAction,
        beforeSnapshot: StructuredPageSnapshot?,
        afterSnapshot: StructuredPageSnapshot?,
        pageDiff: String = "",
    ): AgentVerificationResult {
        if (afterSnapshot == null) {
            return AgentVerificationResult.notApplicable(
                "open_app 已执行，但执行后瞬间未能捕获页面快照（可能仍在切换/被浮层遮挡）；请 wait 后根据截图确认",
            )
        }
        val beforePkg = beforeSnapshot?.packageName.orEmpty()
        val afterPkg = afterSnapshot.packageName
        val resolvedPkg = afterPkg.ifBlank { null }
        if (beforePkg.isNotBlank() && beforePkg == afterPkg &&
            beforeSnapshot?.fingerprint == afterSnapshot.fingerprint
        ) {
            if (VisionScreenChange.screenshotChanged(pageDiff)) {
                return AgentVerificationResult.verified(
                    message = "应用未变但截图已变化（可能为页面内导航）",
                    expected = action.targetText,
                    actual = afterSnapshot.appHint.ifBlank { afterPkg },
                )
            }
            return AgentVerificationResult.failed(
                message = "open_app 后应用与页面均未变化",
                expected = action.targetText,
                actual = afterSnapshot.appHint.ifBlank { resolvedPkg ?: afterPkg },
            )
        }
        return AgentVerificationResult.verified(
            message = "应用已切换或页面已更新",
            expected = action.targetText,
            actual = afterSnapshot.appHint.ifBlank { afterPkg },
        )
    }

    private fun verifyFindOnPage(
        action: AgentAction,
        executionResult: ActionExecutionResult,
    ): AgentVerificationResult {
        val query = action.targetText?.trim().orEmpty()
        if (query.isBlank()) return AgentVerificationResult.notApplicable("find_on_page 无目标文字")
        return if (executionResult.matchedElements.isNotEmpty()) {
            AgentVerificationResult.verified(
                message = "页面可见文字包含目标",
                expected = query,
                actual = executionResult.matchedElements.take(3).joinToString(" | "),
            )
        } else {
            AgentVerificationResult.failed(
                message = "未在页面找到目标文字",
                expected = query,
            )
        }
    }

    private fun verifyUiChanged(
        action: AgentAction,
        beforeSnapshot: StructuredPageSnapshot?,
        afterSnapshot: StructuredPageSnapshot?,
        pageDiff: String,
    ): AgentVerificationResult {
        if (afterSnapshot == null) {
            return AgentVerificationResult.notApplicable(
                "${action.action} 已执行，但执行后瞬间未能捕获页面快照；请 read_tree 或 wait 后再判断",
            )
        }
        if (AgentActionGuard.pageDiffIndicatesNoChangeA11yOnly(pageDiff) &&
            !VisionScreenChange.screenshotChanged(pageDiff)
        ) {
            return AgentVerificationResult.failed(
                message = "操作报告成功但页面指纹未变，可能未推进目标",
                expected = describe(action),
                actual = afterSnapshot.toMinimalSummary(),
            )
        }
        val beforeFp = beforeSnapshot?.fingerprint
        val afterFp = afterSnapshot.fingerprint
        if (beforeFp != null && beforeFp == afterFp &&
            !VisionScreenChange.screenshotChanged(pageDiff)
        ) {
            return AgentVerificationResult.failed(
                message = "操作后页面指纹与动作前相同",
                expected = describe(action),
                actual = afterSnapshot.toMinimalSummary(),
            )
        }
        if (action.action.equals("click", ignoreCase = true) && beforeSnapshot != null) {
            val prevClickables = beforeSnapshot.clickables.toSet()
            val prevEditables = beforeSnapshot.editables.toSet()
            val newClickables = afterSnapshot.clickables.count { it !in prevClickables }
            val removedClickables = beforeSnapshot.clickables.count { it !in afterSnapshot.clickables.toSet() }
            val newEditables = afterSnapshot.editables.count { it !in prevEditables }
            if (beforeSnapshot.packageName == afterSnapshot.packageName &&
                newClickables == 0 && removedClickables == 0 && newEditables == 0
            ) {
                return AgentVerificationResult.failed(
                    message = "点击报告成功但可点击/输入区未变，可能仍在同一屏",
                    expected = describe(action),
                    actual = afterSnapshot.toMinimalSummary(),
                )
            }
            if (beforeSnapshot.packageName == afterSnapshot.packageName &&
                beforeSnapshot.clickables.toSet() == afterSnapshot.clickables.toSet()
            ) {
                return AgentVerificationResult.failed(
                    message = "点击后可见可点击项集合未变，界面可能未实质推进",
                    expected = describe(action),
                    actual = afterSnapshot.toMinimalSummary(),
                )
            }
        }
        return AgentVerificationResult.verified(
            message = "页面已发生变化",
            expected = describe(action),
            actual = afterSnapshot.toMinimalSummary(),
        )
    }

    private fun describe(action: AgentAction): String {
        val target = action.targetText?.let { " target=$it" }.orEmpty()
        val input = action.inputText?.let { " input=${it.take(30)}" }.orEmpty()
        return "${action.action}$target$input"
    }
}
