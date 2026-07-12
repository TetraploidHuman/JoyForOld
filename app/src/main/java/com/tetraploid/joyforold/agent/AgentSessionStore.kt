package com.tetraploid.joyforold.agent

import android.content.Context
import org.json.JSONObject

enum class PendingKind {
    USER_CONFIRM,
    ROUTE_CLARIFY,
    TASK_ABANDON,
    INTENT_DISAMBIGUATION,
    LOCAL_PREVIEW,
    CONTEXT_CONSENT,
}

data class PendingAgentState(
    val originalCommand: String,
    val aiPrompt: String,
    val session: AgentConversationSession,
    val previousSnapshot: StructuredPageSnapshot?,
    val kind: PendingKind = PendingKind.USER_CONFIRM,
    val needsBinaryConfirm: Boolean = false,
    val deferredCommand: String? = null,
    val plannedSteps: List<AgentAction>? = null,
    val suspendedOriginalCommand: String? = null,
    val suspendedAiPrompt: String? = null,
    val suspendedSession: AgentConversationSession? = null,
    val suspendedSnapshot: StructuredPageSnapshot? = null,
    val suspendedNeedsBinaryConfirm: Boolean? = null,
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
            .putString(KEY_PENDING_KIND, state.kind.name)
            .putBoolean(KEY_PENDING_NEEDS_BINARY, state.needsBinaryConfirm)
            .putString(KEY_PENDING_DEFERRED, state.deferredCommand)
            .putString(KEY_PENDING_PLANNED, encodePlannedSteps(state.plannedSteps))
            .putString(KEY_PENDING_SUSPENDED_SESSION, state.suspendedSession?.toJson()?.toString())
            .putString(KEY_PENDING_SUSPENDED_ORIGINAL, state.suspendedOriginalCommand)
            .putString(KEY_PENDING_SUSPENDED_PROMPT, state.suspendedAiPrompt)
            .putString(
                KEY_PENDING_SUSPENDED_SNAPSHOT,
                state.suspendedSnapshot?.toJson()?.toString(),
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
            val kind = prefs.getString(KEY_PENDING_KIND, null)
                ?.let { runCatching { PendingKind.valueOf(it) }.getOrNull() }
                ?: PendingKind.USER_CONFIRM
            val suspendedSessionRaw = prefs.getString(KEY_PENDING_SUSPENDED_SESSION, null)
            val suspendedSession = suspendedSessionRaw?.let {
                runCatching { AgentConversationSession.fromJson(JSONObject(it)) }.getOrNull()
            }
            val suspendedSnapshotRaw = prefs.getString(KEY_PENDING_SUSPENDED_SNAPSHOT, null)
            val suspendedSnapshot = suspendedSnapshotRaw?.let {
                runCatching { StructuredPageSnapshot.fromJson(JSONObject(it)) }.getOrNull()
            }
            PendingAgentState(
                originalCommand = original,
                aiPrompt = prompt,
                session = session,
                previousSnapshot = snapshot,
                kind = kind,
                needsBinaryConfirm = prefs.getBoolean(KEY_PENDING_NEEDS_BINARY, false),
                deferredCommand = prefs.getString(KEY_PENDING_DEFERRED, null),
                plannedSteps = decodePlannedSteps(prefs.getString(KEY_PENDING_PLANNED, null)),
                suspendedOriginalCommand = prefs.getString(KEY_PENDING_SUSPENDED_ORIGINAL, null),
                suspendedAiPrompt = prefs.getString(KEY_PENDING_SUSPENDED_PROMPT, null),
                suspendedSession = suspendedSession,
                suspendedSnapshot = suspendedSnapshot,
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
            .remove(KEY_PENDING_KIND)
            .remove(KEY_PENDING_NEEDS_BINARY)
            .remove(KEY_PENDING_DEFERRED)
            .remove(KEY_PENDING_PLANNED)
            .remove(KEY_PENDING_SUSPENDED_SESSION)
            .remove(KEY_PENDING_SUSPENDED_ORIGINAL)
            .remove(KEY_PENDING_SUSPENDED_PROMPT)
            .remove(KEY_PENDING_SUSPENDED_SNAPSHOT)
            .apply()
    }

    private fun encodePlannedSteps(steps: List<AgentAction>?): String? {
        if (steps.isNullOrEmpty()) return null
        val arr = org.json.JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun decodePlannedSteps(raw: String?): List<AgentAction>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(AgentAction.fromJson(arr.getJSONObject(i)))
                }
            }
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_PENDING_SESSION = "agent_pending_session"
        private const val KEY_PENDING_ORIGINAL = "agent_pending_original"
        private const val KEY_PENDING_PROMPT = "agent_pending_prompt"
        private const val KEY_PENDING_SNAPSHOT = "agent_pending_snapshot"
        private const val KEY_PENDING_KIND = "agent_pending_kind"
        private const val KEY_PENDING_NEEDS_BINARY = "agent_pending_needs_binary"
        private const val KEY_PENDING_DEFERRED = "agent_pending_deferred"
        private const val KEY_PENDING_PLANNED = "agent_pending_planned"
        private const val KEY_PENDING_SUSPENDED_SESSION = "agent_pending_suspended_session"
        private const val KEY_PENDING_SUSPENDED_ORIGINAL = "agent_pending_suspended_original"
        private const val KEY_PENDING_SUSPENDED_PROMPT = "agent_pending_suspended_prompt"
        private const val KEY_PENDING_SUSPENDED_SNAPSHOT = "agent_pending_suspended_snapshot"
    }
}
