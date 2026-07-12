package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.app.InstalledAppResolver

/**
 * Agent 单步动作的白名单 / 守卫 / 执行，自 [AgentOrchestrator] 拆出以降低体量。
 */
internal object AgentGuardedActionExecutor {

    sealed class Outcome {
        data class Blocked(val reason: String) : Outcome()
        data class NeedsConfirm(val action: AgentAction) : Outcome()
        data class Executed(val result: ActionExecutionResult) : Outcome()
    }

    suspend fun execute(
        context: Context,
        service: JoyAccessibilityService?,
        session: AgentConversationSession,
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
    ): Outcome {
        AgentActionWhitelist.blockReason(action.action)?.let { return Outcome.Blocked(it) }

        if (AgentActionPlaybook.isRunPlaybookAction(action)) {
            return Outcome.Blocked("run_playbook 应在规划层展开为具体步骤，不应直接执行")
        }

        AgentActionGuard.sensitiveConfirmOverride(session, action)?.let {
            return Outcome.NeedsConfirm(it)
        }

        val currentSnapshot = snapshot ?: service?.mergeSnapshots(service.captureStructuredSnapshots())
        AgentActionGuard.blockedRepeatReason(
            session,
            action,
            pageUnchangedSinceLastStep = session.stepRecords.lastOrNull()
                ?.pageDiff
                ?.let(AgentActionGuard::pageDiffIndicatesNoChange)
                ?: false,
            a11yUnavailable = PageReadiness.needsVisionFallback(currentSnapshot),
        )?.let { return Outcome.Blocked(it) }

        PageReadiness.needsVisionFallback(currentSnapshot).takeIf { it }?.let {
            AgentActionGuard.blockedInVisionMode(action)?.let { reason ->
                return Outcome.Blocked(reason)
            }
        }

        if (action.action.equals("open_app", ignoreCase = true) && service != null) {
            val targetPkg = InstalledAppResolver.resolvePackage(
                context,
                action.targetText.orEmpty(),
            )
            if (!targetPkg.isNullOrBlank() && currentSnapshot?.packageName == targetPkg) {
                return Outcome.Blocked(
                    "目标应用已在当前前台（$targetPkg），请勿重复 open_app；请根据截图继续 tap/type。",
                )
            }
        }

        RiskScreenGuard.blockReason(currentSnapshot, action)?.let { return Outcome.Blocked(it) }

        AgentToolRegistry.executeSystemIntent(context, action)?.let {
            return Outcome.Executed(it)
        }

        if (service == null) {
            return Outcome.Blocked("需要无障碍服务才能执行：${action.action}")
        }

        val result = AgentToolRegistry.execute(context, service, action)
        return Outcome.Executed(result)
    }
}
