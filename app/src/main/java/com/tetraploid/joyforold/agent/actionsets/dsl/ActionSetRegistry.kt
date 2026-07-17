package com.tetraploid.joyforold.agent.actionsets.dsl

/**
 * ActionSet 定义注册表。各动作组在 object 初始化时 [register]。
 */
object ActionSetRegistry {
    private val definitions = linkedMapOf<String, ActionSetDefinition>()

    fun register(definition: ActionSetDefinition) {
        definitions[definition.id.lowercase()] = definition
    }

    fun get(id: String): ActionSetDefinition? = definitions[id.trim().lowercase()]

    fun all(): Collection<ActionSetDefinition> = definitions.values

    fun promptDescriptions(): String =
        all()
            .mapNotNull { def ->
                def.promptDescription.takeIf { it.isNotBlank() }
            }
            .joinToString("\n")
            .ifBlank {
                all().joinToString("\n") { "- ${it.id}" }
            }
}
