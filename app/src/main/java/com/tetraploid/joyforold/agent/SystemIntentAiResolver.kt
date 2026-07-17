package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.system.AmapPoiResolver

/**
 * 用 AI 理解闹钟/日程/导航等系统能力意图，并映射到原生 SystemIntentExecutor 动作。
 * 避免为口语变体（如「最近/附近/就近」）维护大量正则。
 */
object SystemIntentAiResolver {
    private const val MIN_CONFIDENCE = 0.55

    data class Classification(
        val intent: String,
        val confidence: Double,
        val timeHhmm: String? = null,
        val title: String? = null,
        val notes: String? = null,
        val eventTimeIso: String? = null,
        val destination: String? = null,
        /** 「地标附近的品类」中的地标，如桂阳一中；品类在 destination。 */
        val nearLandmark: String? = null,
        val clarify: String? = null,
    )

    data class ResolvedRoute(
        val steps: List<AgentAction>,
        val confidence: Double,
        val clarifyMessage: String? = null,
    )

    suspend fun resolve(
        command: String,
        apiKey: String,
        client: AgentLlmClient,
    ): ResolvedRoute? {
        val trimmed = command.trim()
        if (trimmed.isBlank() || apiKey.isBlank()) return null

        val classification = client.classifySystemIntent(apiKey, trimmed) ?: return null
        if (classification.intent.equals("none", ignoreCase = true)) return null
        if (classification.confidence < MIN_CONFIDENCE) return null

        val steps = stepsFor(classification) ?: return null
        val needsOpenQuestion = steps.any { it.waitingForUser }
        val clarifyMessage = if (!needsOpenQuestion) {
            clarifyMessageFor(classification)
        } else {
            null
        }
        return ResolvedRoute(
            steps = steps,
            confidence = classification.confidence,
            clarifyMessage = clarifyMessage,
        )
    }

    suspend fun resolveWithConfidence(
        command: String,
        apiKey: String,
        client: AgentLlmClient,
    ): Pair<List<AgentAction>, Double>? {
        val resolved = resolve(command, apiKey, client) ?: return null
        return resolved.steps to resolved.confidence
    }

    fun stepsFor(classification: Classification): List<AgentAction>? {
        if (classification.intent.equals("none", ignoreCase = true)) return null

        return when (classification.intent.lowercase()) {
            "set_alarm" -> stepsForAlarm(classification)
            "add_calendar_event" -> stepsForCalendar(classification)
            "navigate_home" -> listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "正在为您导航回家。", finished = true),
            )
            "navigate_to" -> stepsForNavigateTo(classification)
            "navigate_pick" -> stepsForNavigatePick(classification)
            else -> null
        }
    }

    private fun stepsForNavigateTo(classification: Classification): List<AgentAction>? {
        val (dest, landmark) = resolveNavSlots(classification) ?: return null
        val spoken = if (landmark != null) {
            if (AmapPoiResolver.looksLikeAdminRegion(landmark)) "${landmark}的$dest" else "${landmark}附近的$dest"
        } else {
            dest
        }
        return listOf(
            AgentAction(action = "navigate_to", targetText = dest, inputText = landmark),
            AgentAction(action = "finish", message = "正在为您导航前往：$spoken", finished = true),
        )
    }

    private fun stepsForNavigatePick(classification: Classification): List<AgentAction>? {
        val (dest, landmark) = resolveNavSlots(classification) ?: return null
        return listOf(
            AgentAction(action = "navigate_pick", targetText = dest, inputText = landmark),
        )
    }

    /**
     * 导航槽位：有网时以 LLM 的 destination / near_landmark 为准。
     * 本地拆句只做两件事：
     * 1) AI 已给地标，但 destination 仍是整句「A附近的B」→ 拆出 B
     * 2) AI 没给地标 → 用本地启发式补全（与离线路由同款）
     */
    internal fun resolveNavSlots(classification: Classification): Pair<String, String?>? {
        val rawDest = classification.destination?.trim().orEmpty()
            .ifBlank { classification.title?.trim().orEmpty() }
        if (rawDest.isBlank()) return null

        val aiLandmark = classification.nearLandmark?.trim()?.takeIf { it.length >= 2 }
        if (aiLandmark != null) {
            val poi = SystemIntentLocalParser.splitScopedPoiQuery(rawDest)?.poi
                ?.let { SystemIntentLocalParser.normalizePoiQuery(it) }
                ?: SystemIntentLocalParser.normalizePoiQuery(rawDest)
            return poi to aiLandmark
        }

        val scoped = SystemIntentLocalParser.splitScopedPoiQuery(rawDest)
        if (scoped != null) {
            return SystemIntentLocalParser.normalizePoiQuery(scoped.poi) to scoped.landmark
        }
        return SystemIntentLocalParser.normalizePoiQuery(rawDest) to null
    }

    fun clarifyMessageFor(classification: Classification): String? {
        return classification.clarify?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun stepsForAlarm(classification: Classification): List<AgentAction>? {
        val time = classification.timeHhmm?.trim().orEmpty()
        if (time.isBlank()) {
            val ask = classification.clarify?.trim().orEmpty().ifBlank { "您想设几点的闹钟？" }
            return listOf(
                AgentAction(action = "finish", message = ask, waitingForUser = true),
            )
        }
        val label = classification.title?.trim().orEmpty().ifBlank {
            classification.notes?.trim().orEmpty()
        }
        val spoken = "已打开闹钟设置：$time"
        return listOf(
            AgentAction(action = "set_alarm", targetText = time, inputText = label.ifBlank { null }),
            AgentAction(action = "finish", message = spoken, finished = true),
        )
    }

    private fun stepsForCalendar(classification: Classification): List<AgentAction>? {
        val title = classification.title?.trim().orEmpty().ifBlank { "日程提醒" }
        val inputText = encodeCalendarInput(
            notes = classification.notes.orEmpty(),
            eventTimeIso = classification.eventTimeIso,
        )
        val spoken = if (classification.eventTimeIso.isNullOrBlank()) {
            "已打开日历新建事件：$title"
        } else {
            "已打开日历新建事件：$title（时间已预填）"
        }
        return listOf(
            AgentAction(action = "add_calendar_event", targetText = title, inputText = inputText.ifBlank { null }),
            AgentAction(action = "finish", message = spoken, finished = true),
        )
    }

    internal fun encodeCalendarInput(notes: String, eventTimeIso: String?): String {
        val cleanNotes = notes.trim()
        val iso = eventTimeIso?.trim().orEmpty()
        if (iso.isBlank()) return cleanNotes
        return if (cleanNotes.isBlank()) "@t=$iso" else "@t=$iso|$cleanNotes"
    }
}
