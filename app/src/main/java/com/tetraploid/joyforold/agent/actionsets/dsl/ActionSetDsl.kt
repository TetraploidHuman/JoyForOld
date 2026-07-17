package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction

@DslMarker
annotation class ActionSetDslMarker

@ActionSetDslMarker
class ActionSetBuilder(private val id: String) {
    private val paramSpecs = mutableListOf<ParamSpec>()
    private var uiLabelFn: (ActionSetParams) -> String = { "动作组：$id" }
    private var promptDescription: String = ""
    private var flowBuilder: FlowBuilder? = null

    fun params(block: ParamsBuilder.() -> Unit) {
        ParamsBuilder(paramSpecs).block()
    }

    fun uiLabel(block: (ActionSetParams) -> String) {
        uiLabelFn = block
    }

    fun promptDescription(text: String) {
        promptDescription = text.trimIndent()
    }

    fun flow(block: FlowBuilder.() -> Unit) {
        flowBuilder = FlowBuilder().apply(block)
    }

    fun build(): ActionSetDefinition {
        val flow = flowBuilder ?: error("actionSet(\"$id\") 需要 flow { }")
        val phases = flow.buildPhases()
        require(phases.isNotEmpty()) { "actionSet(\"$id\") flow 不能为空" }
        return ActionSetDefinition(
            id = id,
            paramSpecs = paramSpecs.toList(),
            phases = phases,
            startPhaseId = flow.startPhaseId ?: phases.keys.first(),
            uiLabel = uiLabelFn,
            promptDescription = promptDescription,
        )
    }
}

@ActionSetDslMarker
class ParamsBuilder(
    private val specs: MutableList<ParamSpec>,
) {
    fun required(name: String, from: ParamSource) {
        specs += ParamSpec(name = name, required = true, source = from)
    }

    fun optional(name: String, from: ParamSource? = null, default: String) {
        specs += ParamSpec(name = name, required = false, source = from, defaultValue = default)
    }
}

@ActionSetDslMarker
class FlowBuilder {
    private val phases = linkedMapOf<String, PhaseBuilder>()
    var startPhaseId: String? = null
        private set

    fun phase(id: String, block: PhaseBuilder.() -> Unit) {
        val builder = PhaseBuilder(id).apply(block)
        if (phases.isEmpty()) startPhaseId = id
        phases[id] = builder
    }

    fun buildPhases(): Map<String, PhaseDefinition> {
        val ids = phases.keys.toList()
        return phases.mapValues { (id, builder) ->
            val index = ids.indexOf(id)
            val defaultNext = ids.getOrNull(index + 1)
            builder.build(defaultNext = defaultNext)
        }
    }
}

@ActionSetDslMarker
class PhaseBuilder(private val id: String) {
    private val actions = mutableListOf<ActionFactory>()
    private var onSuccess: String? = null
    private var onFail: String? = null
    private var next: String? = null
    private var branchIndex: Int? = null
    private var captureInto: String? = null
    private var askLlmSpec: PhaseKind.AskLlm? = null

    fun openApp(target: StringRef) = add { AgentAction(action = "open_app", targetText = target.resolve(it)) }
    fun openApp(target: String) = openApp(lit(target))

    fun click(target: StringRef) = add { AgentAction(action = "click", targetText = target.resolve(it)) }
    fun click(target: String) = click(lit(target))

    fun findOnPage(target: StringRef) = add { AgentAction(action = "find_on_page", targetText = target.resolve(it)) }
    fun findOnPage(target: String) = findOnPage(lit(target))

    fun type(text: StringRef, target: StringRef? = null) = add {
        AgentAction(
            action = "type",
            targetText = target?.resolve(it),
            inputText = text.resolve(it),
        )
    }

    fun type(text: String, target: String? = null) = type(lit(text), target?.let { lit(it) })

    fun type(text: StringRef, target: String) = type(text, lit(target))

    fun type(text: String, target: StringRef) = type(lit(text), target)

    fun send() = add { AgentAction(action = "send") }

    fun waitUi() = add { AgentAction(action = "wait") }

    fun finish(message: StringRef) = add {
        AgentAction(
            action = "finish",
            message = message.resolve(it),
            finished = true,
        )
    }

    fun finish(message: String) = finish(lit(message))

    fun finish(message: (ActionSetParams) -> String) = add {
        AgentAction(action = "finish", message = message(it), finished = true)
    }

    /** 把当前页可点击/可见文字采到 [into] 参数（| 分隔），供后续 askLlm 使用。 */
    fun capturePageTexts(into: String) {
        require(actions.isEmpty() && askLlmSpec == null) {
            "phase(\"$id\") 的 capturePageTexts 不能与普通动作 / askLlm 混用"
        }
        captureInto = into
    }

    /**
     * 窄域询问 LLM：把 JSON 字段写回 params。
     * 例：`askLlm(writeTo = listOf("contact")) { system("..."); user { ... } }`
     */
    fun askLlm(writeTo: List<String>, block: AskLlmBuilder.() -> Unit) {
        require(actions.isEmpty() && captureInto == null) {
            "phase(\"$id\") 的 askLlm 不能与普通动作 / capturePageTexts 混用"
        }
        require(writeTo.isNotEmpty()) { "askLlm writeTo 不能为空" }
        val builder = AskLlmBuilder().apply(block)
        askLlmSpec = PhaseKind.AskLlm(
            writeFields = writeTo,
            systemPrompt = builder.systemPrompt,
            userPrompt = builder.userPrompt,
        )
    }

    fun onSuccess(phaseId: String) {
        onSuccess = phaseId
    }

    fun onFail(phaseId: String) {
        onFail = phaseId
    }

    fun goto(phaseId: String) {
        next = phaseId
    }

    /** 显式指定用哪一步做分支判定（默认：第一个非 wait）。 */
    fun branchOnStep(index: Int) {
        branchIndex = index
    }

    private fun add(factory: ActionFactory) {
        require(captureInto == null && askLlmSpec == null) {
            "phase(\"$id\") 已是 capture/askLlm 相位，不能再加动作"
        }
        actions += factory
    }

    fun build(defaultNext: String?): PhaseDefinition {
        val kind = when {
            askLlmSpec != null -> askLlmSpec!!
            captureInto != null -> PhaseKind.CapturePageTexts(captureInto!!)
            else -> {
                val resolvedBranch = branchIndex ?: actions.indices.firstOrNull { i ->
                    val dummy = ActionSetParams(emptyMap())
                    !actions[i](dummy).action.equals("wait", ignoreCase = true)
                } ?: 0
                PhaseKind.Actions(
                    actions = actions.toList(),
                    branchIndex = resolvedBranch,
                    onSuccess = onSuccess,
                    onFail = onFail,
                )
            }
        }
        return PhaseDefinition(
            id = id,
            kind = kind,
            next = next ?: defaultNext,
        )
    }
}

@ActionSetDslMarker
class AskLlmBuilder {
    internal var systemPrompt: (ActionSetParams) -> String = {
        "你是动作组内的窄域助手。只返回 JSON 对象，不要多余文字。"
    }
    internal var userPrompt: (ActionSetParams) -> String = { "" }

    fun system(text: String) {
        systemPrompt = { text }
    }

    fun system(block: (ActionSetParams) -> String) {
        systemPrompt = block
    }

    fun user(text: String) {
        userPrompt = { text }
    }

    fun user(block: (ActionSetParams) -> String) {
        userPrompt = block
    }
}

sealed class StringRef {
    abstract fun resolve(params: ActionSetParams): String
}

private data class LiteralRef(val value: String) : StringRef() {
    override fun resolve(params: ActionSetParams): String = value
}

private data class ParamRef(val name: String) : StringRef() {
    override fun resolve(params: ActionSetParams): String = params[name]
}

private data class DynamicRef(val block: (ActionSetParams) -> String) : StringRef() {
    override fun resolve(params: ActionSetParams): String = block(params)
}

fun lit(value: String): StringRef = LiteralRef(value)
fun param(name: String): StringRef = ParamRef(name)
fun dyn(block: (ActionSetParams) -> String): StringRef = DynamicRef(block)

fun actionSet(id: String, block: ActionSetBuilder.() -> Unit): ActionSetDefinition =
    ActionSetBuilder(id).apply(block).build()
