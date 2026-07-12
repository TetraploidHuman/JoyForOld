package com.tetraploid.joyforold.agent

import android.content.Context

data class ProactiveNudge(
    val id: String,
    val spokenMessage: String,
    val suggestionChip: String? = null,
)

/**
 * 主动式助理：用药/久坐等轻量提醒（前台 tick，不依赖 WorkManager）。
 */
class ProactiveAssistantEngine(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordInteraction(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_INTERACTION_MS, nowMs).apply()
    }

    fun peekNudge(
        memories: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProactiveNudge? {
        val lastInteraction = prefs.getLong(KEY_LAST_INTERACTION_MS, nowMs)
        val inactivityMs = nowMs - lastInteraction
        if (inactivityMs >= INACTIVITY_NUDGE_MS && !recentlyShown(NUDGE_INACTIVITY, nowMs)) {
            markShown(NUDGE_INACTIVITY, nowMs)
            return ProactiveNudge(
                id = NUDGE_INACTIVITY,
                spokenMessage = "您好，需要我帮您做点什么吗？",
                suggestionChip = "几点了",
            )
        }

        val memoryText = memories.joinToString(" ")
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
        if ((memoryText.contains("药") || memoryText.contains("吃药")) &&
            hour in 8..20 &&
            !recentlyShown(NUDGE_MEDICATION, nowMs)
        ) {
            markShown(NUDGE_MEDICATION, nowMs)
            return ProactiveNudge(
                id = NUDGE_MEDICATION,
                spokenMessage = "到吃药时间了，需要我帮您设个提醒吗？",
                suggestionChip = "设个吃药提醒",
            )
        }
        return null
    }

    private fun recentlyShown(id: String, nowMs: Long): Boolean {
        val last = prefs.getLong(keyLastShown(id), 0L)
        return nowMs - last < NUDGE_COOLDOWN_MS
    }

    private fun markShown(id: String, nowMs: Long) {
        prefs.edit().putLong(keyLastShown(id), nowMs).apply()
    }

    private fun keyLastShown(id: String) = "nudge_last_$id"

    companion object {
        private const val PREFS_NAME = "joy_proactive_assistant"
        private const val KEY_LAST_INTERACTION_MS = "last_interaction_ms"
        private const val INACTIVITY_NUDGE_MS = 2 * 60 * 60 * 1000L
        private const val NUDGE_COOLDOWN_MS = 4 * 60 * 60 * 1000L
        private const val NUDGE_INACTIVITY = "inactivity"
        private const val NUDGE_MEDICATION = "medication"
    }
}
