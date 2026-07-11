package com.tetraploid.joyforold.agent

/**
 * 用 AI 理解闹钟/日程等系统能力意图，并映射到原生 SystemIntentExecutor 动作。
 * 避免为口语变体维护大量正则。
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
        client: DeepSeekClient,
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
        client: DeepSeekClient,
    ): Pair<List<AgentAction>, Double>? {
        val resolved = resolve(command, apiKey, client) ?: return null
        return resolved.steps to resolved.confidence
    }

    fun stepsFor(classification: Classification): List<AgentAction>? {
        if (classification.intent.equals("none", ignoreCase = true)) return null

        return when (classification.intent.lowercase()) {
            "set_alarm" -> stepsForAlarm(classification)
            "add_calendar_event" -> stepsForCalendar(classification)
            else -> null
        }
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
