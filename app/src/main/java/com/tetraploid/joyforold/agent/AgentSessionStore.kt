package com.tetraploid.joyforold.agent

import android.content.Context
import org.json.JSONObject

data class PendingAgentState(
    val originalCommand: String,
    val aiPrompt: String,
    val session: AgentConversationSession,
    val previousSnapshot: StructuredPageSnapshot?,
)

class AgentSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePending(state: PendingAgentState) {
        prefs.edit()
            .putString(KEY_PENDING_SESSION, state.session.toJson().toString())
            .putString(KEY_PENDING_ORIGINAL, state.originalCommand)
            .putString(KEY_PENDING_PROMPT, state.aiPrompt)
            .putString(
                KEY_PENDING_SNAPSHOT,
                state.previousSnapshot?.toJson()?.toString(),
            )
            .apply()
    }

    fun loadPending(): PendingAgentState? {
        val sessionRaw = prefs.getString(KEY_PENDING_SESSION, null) ?: return null
        val original = prefs.getString(KEY_PENDING_ORIGINAL, null) ?: return null
        val prompt = prefs.getString(KEY_PENDING_PROMPT, null) ?: return null
        return try {
            val session = AgentConversationSession.fromJson(JSONObject(sessionRaw))
            val snapshotRaw = prefs.getString(KEY_PENDING_SNAPSHOT, null)
            val snapshot = snapshotRaw?.let {
                StructuredPageSnapshot.fromJson(JSONObject(it))
            }
            PendingAgentState(
                originalCommand = original,
                aiPrompt = prompt,
                session = session,
                previousSnapshot = snapshot,
            )
        } catch (_: Exception) {
            clearPending()
            null
        }
    }

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_SESSION)
            .remove(KEY_PENDING_ORIGINAL)
            .remove(KEY_PENDING_PROMPT)
            .remove(KEY_PENDING_SNAPSHOT)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_PENDING_SESSION = "agent_pending_session"
        private const val KEY_PENDING_ORIGINAL = "agent_pending_original"
        private const val KEY_PENDING_PROMPT = "agent_pending_prompt"
        private const val KEY_PENDING_SNAPSHOT = "agent_pending_snapshot"
    }
}
