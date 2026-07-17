package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction

/** 运行时已解析的参数表。 */
data class ActionSetParams(
    val values: Map<String, String>,
) {
    operator fun get(name: String): String = values[name].orEmpty()

    fun getOrNull(name: String): String? = values[name]?.takeIf { it.isNotBlank() }
}

enum class ParamSource {
    /** [AgentAction.inputText] */
    INPUT_TEXT,
    /** [AgentAction.message] */
    MESSAGE,
    /** [AgentAction.targetText] — 少见，一般留给动作组 id */
    TARGET_TEXT,
}

data class ParamSpec(
    val name: String,
    val required: Boolean,
    val source: ParamSource? = null,
    val defaultValue: String? = null,
)

fun parseParams(
    specs: List<ParamSpec>,
    action: AgentAction,
): ActionSetParams? {
    val values = linkedMapOf<String, String>()
    for (spec in specs) {
        val raw = when (spec.source) {
            ParamSource.INPUT_TEXT -> action.inputText
            ParamSource.MESSAGE -> action.message
            ParamSource.TARGET_TEXT -> action.targetText
            null -> null
        }?.trim().orEmpty()
        val resolved = raw.ifBlank { spec.defaultValue.orEmpty() }
        if (spec.required && resolved.isBlank()) return null
        if (resolved.isNotBlank()) {
            values[spec.name] = resolved
        } else if (spec.defaultValue != null) {
            values[spec.name] = spec.defaultValue
        }
    }
    return ActionSetParams(values)
}
