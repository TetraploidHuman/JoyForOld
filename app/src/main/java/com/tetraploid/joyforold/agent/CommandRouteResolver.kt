package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.offline.nlu.OfflineNluRouter
import com.tetraploid.joyforold.preset.PresetCommand
import com.tetraploid.joyforold.preset.PresetTextNormalizer
import com.tetraploid.joyforold.accessibility.AccessibilityPermission
import com.tetraploid.joyforold.util.NetworkStatus

/**
 * 统一指令路由：模板 / 离线 ONNX NLU / 本地系统快捷 / AI 系统意图 / 本地结构指令 / 照护者预设 / AI 分类。
 *
 * 精度优先：宁可 NLU 失败走 DeepSeek，不接受 NLU 错误匹配。
 * 能力矩阵见 [IntentCapabilityMatrix]。
 */
object CommandRouteResolver {
    const val AUTO_EXECUTE_THRESHOLD = 0.85
    const val CLARIFY_THRESHOLD = 0.55

    data class Route(
        val steps: List<AgentAction>,
        val source: String,
        val confidence: Double,
        val clarifyMessage: String? = null,
    )

    suspend fun resolve(
        command: String,
        apiKey: String,
        deepSeekClient: DeepSeekClient,
        presetCommands: List<PresetCommand> = emptyList(),
        appContext: Context? = null,
    ): Route? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null

        val env = routeEnvironment(appContext)
        val candidates = mutableListOf<Route>()

        ElderTaskTemplateMatcher.match(trimmed)?.let { steps ->
            candidates += Route(steps, source = "template", confidence = 1.0)
        }

        OfflineNluRouter.match(trimmed, appContext)?.let { offline ->
            if (shouldUseOfflineNlu(trimmed, offline, appContext)) {
                candidates += Route(
                    steps = offline.steps,
                    source = "offline_nlu",
                    confidence = offline.confidence,
                    clarifyMessage = offline.clarifyMessage,
                )
            }
        }

        SystemIntentLocalParser.parse(trimmed, appContext)?.let { steps ->
            candidates += Route(
                steps = steps,
                source = "system_intent_local",
                confidence = 0.97,
            )
        }

        if (env.hasNetwork) {
            SystemIntentAiResolver.resolve(trimmed, apiKey, deepSeekClient)?.let { resolved ->
                candidates += Route(
                    steps = resolved.steps,
                    source = "system_ai",
                    confidence = resolved.confidence,
                    clarifyMessage = resolved.clarifyMessage,
                )
            }
        }

        LocalCommandParser.parse(trimmed)?.let { steps ->
            candidates += Route(steps, source = "local", confidence = 0.95)
        }

        presetCommands.firstOrNull { preset ->
            preset.aliases.any { PresetTextNormalizer.normalize(it) == PresetTextNormalizer.normalize(trimmed) }
        }?.let { preset ->
            actionsForPreset(preset)?.let { steps ->
                candidates += Route(steps, source = "preset", confidence = 0.92)
            }
        }

        if (env.hasNetwork) {
            PresetIntentResolver.resolveWithConfidence(trimmed, apiKey, deepSeekClient)?.let { (steps, confidence) ->
                candidates += Route(steps, source = "preset_ai", confidence = confidence)
            }
        }

        val viable = candidates.filter { IntentCapabilityMatrix.isRouteAllowed(it, env) }
        val best = viable.maxByOrNull { it.confidence } ?: return null
        return when {
            best.confidence >= AUTO_EXECUTE_THRESHOLD -> best
            best.confidence >= CLARIFY_THRESHOLD -> best.copy(
                clarifyMessage = buildClarifyMessage(trimmed, best),
            )
            else -> null
        }
    }

    private fun routeEnvironment(appContext: Context?): IntentCapabilityMatrix.RouteEnvironment {
        val hasNetwork = appContext?.let { NetworkStatus.hasInternet(it) } ?: true
        val hasA11y = AccessibilityPermission.isServiceConnected()
        return IntentCapabilityMatrix.RouteEnvironment(
            hasNetwork = hasNetwork,
            hasAccessibility = hasA11y,
            online = hasNetwork,
        )
    }

    internal fun shouldUseOfflineNlu(
        command: String,
        offline: OfflineNluRouter.Match,
        appContext: Context?,
    ): Boolean {
        val env = routeEnvironment(appContext)
        return IntentCapabilityMatrix.shouldUseOfflineNlu(
            command = command,
            intentId = offline.intent,
            routeConfidence = offline.confidence,
            env = env,
        )
    }

    internal fun looksLikeComplexQuery(command: String): Boolean =
        IntentCapabilityMatrix.isComplexQuery(command)

    internal fun looksLikeMultiStepUtterance(command: String): Boolean =
        IntentCapabilityMatrix.isMultiStepUtterance(command)

    fun buildClarifyMessage(command: String, route: Route): String {
        route.clarifyMessage?.trim()?.takeIf { it.isNotBlank() }?.let { aiHint ->
            return if (aiHint.contains("确认")) {
                aiHint
            } else {
                "$aiHint 请说「确认」或「取消」。"
            }
        }
        val hint = route.steps.lastOrNull { it.action.equals("finish", ignoreCase = true) }
            ?.message
            ?.trim()
            .orEmpty()
        return if (hint.isNotBlank()) {
            "您是要$hint 吗？请说「确认」或「取消」。"
        } else {
            "没太听清您的意思（「$command」），您是要执行${route.source}指令吗？请说「确认」或「取消」。"
        }
    }

    private fun actionsForPreset(preset: PresetCommand): List<AgentAction>? {
        return PresetIntentResolver.stepsForIntent(preset.action)
    }
}
