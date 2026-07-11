package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.preset.PresetCommand
import com.tetraploid.joyforold.preset.PresetTextNormalizer

/**
 * 统一指令路由：模板 / 本地系统快捷 / AI 系统意图 / 本地结构指令 / 照护者预设 / AI 分类，按置信度决策。
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

        val candidates = mutableListOf<Route>()

        ElderTaskTemplateMatcher.match(trimmed)?.let { steps ->
            candidates += Route(steps, source = "template", confidence = 1.0)
        }

        LocalSystemShortcutResolver.match(trimmed, appContext)?.let { shortcut ->
            candidates += Route(
                steps = shortcut.steps,
                source = "local_system",
                confidence = shortcut.confidence,
            )
        }

        SystemIntentAiResolver.resolve(trimmed, apiKey, deepSeekClient)?.let { resolved ->
            candidates += Route(
                steps = resolved.steps,
                source = "system_ai",
                confidence = resolved.confidence,
                clarifyMessage = resolved.clarifyMessage,
            )
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

        PresetIntentResolver.resolveWithConfidence(trimmed, apiKey, deepSeekClient)?.let { (steps, confidence) ->
            candidates += Route(steps, source = "preset_ai", confidence = confidence)
        }

        val best = candidates.maxByOrNull { it.confidence } ?: return null
        return when {
            best.confidence >= AUTO_EXECUTE_THRESHOLD -> best
            best.confidence >= CLARIFY_THRESHOLD -> best.copy(
                clarifyMessage = buildClarifyMessage(trimmed, best),
            )
            else -> null
        }
    }

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
