package com.tetraploid.joyforold.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class KeyMemory(
    val id: String,
    val summary: String,
    val userCommand: String,
    val outcome: String,
    val createdAt: Long,
    val tags: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("summary", summary)
        put("user_command", userCommand)
        put("outcome", outcome)
        put("created_at", createdAt)
        put("tags", JSONArray(tags))
    }

    companion object {
        fun fromJson(json: JSONObject): KeyMemory = KeyMemory(
            id = json.optString("id", UUID.randomUUID().toString()),
            summary = json.optString("summary"),
            userCommand = json.optString("user_command"),
            outcome = json.optString("outcome"),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            tags = json.optJSONArray("tags")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) add(arr.optString(i))
                }
            } ?: emptyList(),
        )
    }
}

class AgentMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRecentMemories(limit: Int = MAX_MEMORIES): List<KeyMemory> {
        val raw = prefs.getString(KEY_MEMORIES, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(KeyMemory.fromJson(arr.getJSONObject(i)))
                }
            }.sortedByDescending { it.createdAt }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun formatMemoriesForPrompt(memories: List<KeyMemory>): String {
        if (memories.isEmpty()) return "（暂无历史记忆）"
        return memories.take(12).joinToString("\n") { memory ->
            "- [${memory.outcome}] ${memory.summary}（指令：${memory.userCommand.take(40)}）"
        }
    }

    fun saveKeyMemory(memory: KeyMemory) {
        val current = loadRecentMemories(MAX_MEMORIES + 10).toMutableList()
        current.removeAll { it.id == memory.id }
        current.add(0, memory)
        val trimmed = current.take(MAX_MEMORIES)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_MEMORIES, arr.toString()).apply()
    }

    fun saveFromSession(session: AgentConversationSession, extractedSummary: String) {
        if (extractedSummary.isBlank()) return
        val outcome = when (session.status) {
            "success" -> "成功"
            "waiting_user" -> "待确认"
            "cancelled" -> "已取消"
            else -> "失败"
        }
        saveKeyMemory(
            KeyMemory(
                id = session.sessionId,
                summary = extractedSummary,
                userCommand = session.rootCommand,
                outcome = outcome,
                createdAt = System.currentTimeMillis(),
                tags = inferTags(session.rootCommand, extractedSummary),
            ),
        )
    }

    private fun inferTags(command: String, summary: String): List<String> {
        val text = (command + summary).lowercase()
        return buildList {
            if (text.contains("电话") || text.contains("拨打") || text.contains("呼叫")) add("打电话")
            if (text.contains("发消息") || text.contains("发送") || text.contains("微信") || text.contains("qq")) add("发消息")
            if (text.contains("联系人") || text.contains("好友")) add("联系人")
        }
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_MEMORIES = "agent_key_memories"
        private const val MAX_MEMORIES = 40
    }
}
