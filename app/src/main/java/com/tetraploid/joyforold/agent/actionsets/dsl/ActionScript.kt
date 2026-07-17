package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.AgentAction

/**
 * 线性脚本式 ActionSet DSL：少写 phase/goto，编译为现有 [ActionSetDefinition] phase 图。
 *
 * ActionSet 的价值是：**固定步骤本地跑**，少把整棵 UI 树塞进主规划 LLM。
 * `captureTexts` 采当前页会变的文案切片（列表项等）；`askLlm` 只基于该切片选型，
 * 不要写成「纠语音错字」的独立能力——纠错只是选型时的附带效果。
 *
 * 例：
 * ```
 * actionScript("demo") {
 *   require("q", from = ParamSource.INPUT_TEXT)
 *   openApp("微信").wait()
 *   captureTexts(into = "pageTexts")
 *   askLlm(writeTo = listOf("q"), system = "...", user = { "..." })
 *   find(param("q")) {
 *     ok { click(param("q")).wait() }
 *     miss { click("搜索"); click(param("q")).wait() }
 *   }
 *   finish("完成")
 * }
 * ```
 */
fun actionScript(
    id: String,
    register: Boolean = true,
    block: ActionScriptBuilder.() -> Unit,
): ActionSetDefinition {
    val def = ActionScriptBuilder(id).apply(block).build()
    if (register) ActionSetRegistry.register(def)
    return def
}

@ActionSetDslMarker
class ActionScriptBuilder(
    private val id: String,
) {
    private val paramSpecs = mutableListOf<ParamSpec>()
    private var uiLabelFn: (ActionSetParams) -> String = { "动作组：$id" }
    private val stmts = mutableListOf<ScriptStmt>()
    private val actionBuf = mutableListOf<ActionFactory>()
    private var pendingLabel: String? = null
    private var seq = 0

    fun uiLabel(block: (ActionSetParams) -> String) {
        uiLabelFn = block
    }

    fun require(name: String, from: ParamSource) {
        paramSpecs += ParamSpec(name, required = true, source = from)
    }

    fun optional(name: String, from: ParamSource? = null, default: String = "") {
        if (paramSpecs.none { it.name == name }) {
            paramSpecs += ParamSpec(name, required = false, source = from, defaultValue = default)
        }
    }

    fun label(name: String) {
        flushActions()
        pendingLabel = name
    }

    fun openApp(target: StringRef): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "open_app", targetText = target.resolve(it)) }
    }

    fun openApp(target: String): ActionScriptBuilder = openApp(lit(target))

    fun navigateTo(dest: StringRef): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "navigate_to", targetText = dest.resolve(it)) }
    }

    fun navigateTo(dest: String): ActionScriptBuilder = navigateTo(lit(dest))

    fun click(target: StringRef): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "click", targetText = target.resolve(it)) }
    }

    fun click(target: String): ActionScriptBuilder = click(lit(target))

    fun findOnPage(target: StringRef): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "find_on_page", targetText = target.resolve(it)) }
    }

    fun type(text: StringRef, into: String? = null): ActionScriptBuilder = apply {
        actionBuf += {
            AgentAction(
                action = "type",
                targetText = into,
                inputText = text.resolve(it),
            )
        }
    }

    fun type(text: String, into: String? = null): ActionScriptBuilder = type(lit(text), into)

    fun send(): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "send") }
    }

    fun wait(): ActionScriptBuilder = apply {
        actionBuf += { AgentAction(action = "wait") }
    }

    fun finish(message: String): ActionScriptBuilder = apply {
        actionBuf += {
            AgentAction(action = "finish", message = message, finished = true)
        }
    }

    fun finish(message: (ActionSetParams) -> String): ActionScriptBuilder = apply {
        actionBuf += {
            AgentAction(action = "finish", message = message(it), finished = true)
        }
    }

    /**
     * 采当前页可点/可见文案切片写入 [into]（| 分隔），供后续 [askLlm] 使用。
     * 这是「会变化的部分」（列表等），不是整棵 UI 树。
     */
    fun captureTexts(into: String): ActionScriptBuilder = apply {
        flushActions()
        optional(into, default = "")
        stmts += ScriptStmt.Capture(into)
    }

    /**
     * 窄域问 LLM：只看已采到的列表/候选切片，选出要点击的目标，把 [writeTo] 写回 params。
     * prompt 由调用方提供；框架不做「纠错」之类的业务假设。
     */
    fun askLlm(
        writeTo: List<String>,
        system: String,
        user: (ActionSetParams) -> String,
    ): ActionScriptBuilder = apply {
        flushActions()
        require(writeTo.isNotEmpty()) { "askLlm writeTo 不能为空" }
        stmts += ScriptStmt.AskLlm(
            writeFields = writeTo,
            systemPrompt = { system },
            userPrompt = user,
        )
    }

    fun find(target: StringRef, block: FindBranchBuilder.() -> Unit): ActionScriptBuilder = apply {
        flushActions()
        val branch = FindBranchBuilder().apply(block)
        stmts += ScriptStmt.Find(
            target = target,
            ok = branch.okActions,
            miss = branch.missActions,
        )
    }

    fun find(target: String, block: FindBranchBuilder.() -> Unit): ActionScriptBuilder =
        find(lit(target), block)

    private fun flushActions() {
        if (actionBuf.isEmpty()) return
        stmts += ScriptStmt.Actions(label = pendingLabel, actions = actionBuf.toList())
        actionBuf.clear()
        pendingLabel = null
    }

    fun build(): ActionSetDefinition {
        flushActions()
        require(stmts.isNotEmpty()) { "actionScript(\"$id\") 不能为空" }
        val phases = linkedMapOf<String, PhaseDefinition>()
        val startId = compileSequence(
            stmts = stmts,
            endNext = null,
            phases = phases,
            preferredStart = null,
        )
        return ActionSetDefinition(
            id = id,
            paramSpecs = paramSpecs.toList(),
            phases = phases,
            startPhaseId = startId,
            uiLabel = uiLabelFn,
        )
    }

    private fun freshId(prefix: String): String = "${prefix}_${seq++}"

    private fun compileSequence(
        stmts: List<ScriptStmt>,
        endNext: String?,
        phases: MutableMap<String, PhaseDefinition>,
        preferredStart: String?,
    ): String {
        if (stmts.isEmpty()) {
            error("内部错误：空语句序列")
        }
        val head = stmts.first()
        val rest = stmts.drop(1)
        val restStart: String? = if (rest.isEmpty()) {
            null
        } else {
            compileSequence(rest, endNext, phases, preferredStart = null)
        }
        val nextAfterHead = restStart ?: endNext

        return when (head) {
            is ScriptStmt.Actions -> {
                val phaseId = head.label ?: preferredStart ?: freshId("step")
                phases[phaseId] = PhaseDefinition(
                    id = phaseId,
                    kind = PhaseKind.Actions(actions = head.actions),
                    next = nextAfterHead,
                )
                phaseId
            }
            is ScriptStmt.Capture -> {
                val phaseId = preferredStart ?: freshId("capture")
                phases[phaseId] = PhaseDefinition(
                    id = phaseId,
                    kind = PhaseKind.CapturePageTexts(intoParam = head.into),
                    next = nextAfterHead,
                )
                phaseId
            }
            is ScriptStmt.AskLlm -> {
                val phaseId = preferredStart ?: freshId("ask")
                phases[phaseId] = PhaseDefinition(
                    id = phaseId,
                    kind = PhaseKind.AskLlm(
                        writeFields = head.writeFields,
                        systemPrompt = head.systemPrompt,
                        userPrompt = head.userPrompt,
                    ),
                    next = nextAfterHead,
                )
                phaseId
            }
            is ScriptStmt.Find -> {
                val probeId = preferredStart ?: freshId("probe")
                val okId = freshId("ok")
                val missId = freshId("miss")
                val joinId = nextAfterHead

                phases[probeId] = PhaseDefinition(
                    id = probeId,
                    kind = PhaseKind.Actions(
                        actions = listOf { p ->
                            AgentAction(
                                action = "find_on_page",
                                targetText = head.target.resolve(p),
                            )
                        },
                        branchIndex = 0,
                        onSuccess = okId,
                        onFail = missId,
                    ),
                    next = null,
                )
                phases[okId] = PhaseDefinition(
                    id = okId,
                    kind = PhaseKind.Actions(actions = head.ok),
                    next = joinId,
                )
                phases[missId] = PhaseDefinition(
                    id = missId,
                    kind = PhaseKind.Actions(actions = head.miss),
                    next = joinId,
                )
                probeId
            }
        }
    }
}

@ActionSetDslMarker
class FindBranchBuilder {
    internal var okActions: List<ActionFactory> = emptyList()
    internal var missActions: List<ActionFactory> = emptyList()

    fun ok(block: LinearStepsBuilder.() -> Unit) {
        okActions = LinearStepsBuilder().apply(block).actions
    }

    fun miss(block: LinearStepsBuilder.() -> Unit) {
        missActions = LinearStepsBuilder().apply(block).actions
    }
}

@ActionSetDslMarker
class LinearStepsBuilder {
    internal val actions = mutableListOf<ActionFactory>()

    fun click(target: StringRef): LinearStepsBuilder = apply {
        actions += { AgentAction(action = "click", targetText = target.resolve(it)) }
    }

    fun click(target: String): LinearStepsBuilder = click(lit(target))

    fun type(text: StringRef, into: String? = null): LinearStepsBuilder = apply {
        actions += {
            AgentAction(
                action = "type",
                targetText = into,
                inputText = text.resolve(it),
            )
        }
    }

    fun type(text: String, into: String? = null): LinearStepsBuilder = type(lit(text), into)

    fun wait(): LinearStepsBuilder = apply {
        actions += { AgentAction(action = "wait") }
    }

    fun openApp(target: StringRef): LinearStepsBuilder = apply {
        actions += { AgentAction(action = "open_app", targetText = target.resolve(it)) }
    }

    fun openApp(target: String): LinearStepsBuilder = openApp(lit(target))

    fun send(): LinearStepsBuilder = apply {
        actions += { AgentAction(action = "send") }
    }
}

private sealed class ScriptStmt {
    data class Actions(
        val label: String?,
        val actions: List<ActionFactory>,
    ) : ScriptStmt()

    data class Capture(val into: String) : ScriptStmt()

    data class AskLlm(
        val writeFields: List<String>,
        val systemPrompt: (ActionSetParams) -> String,
        val userPrompt: (ActionSetParams) -> String,
    ) : ScriptStmt()

    data class Find(
        val target: StringRef,
        val ok: List<ActionFactory>,
        val miss: List<ActionFactory>,
    ) : ScriptStmt()
}
