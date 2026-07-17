package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentStepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionScriptTest {

    @Test
    fun compilesLinearScript_toWorkingDefinition() {
        val def = actionScript("demo_script", register = false) {
            require("name", from = ParamSource.INPUT_TEXT)
            optional("appName", default = "DemoApp")
            openApp(param("appName")).wait()
            captureTexts(into = "pageTexts")
            askLlm(
                writeTo = listOf("name"),
                system = "从候选里选一个名字，返回 JSON {\"name\":\"...\"}",
                user = { p -> "说：${p["name"]}；候选：${p["pageTexts"]}" },
            )
            find(param("name")) {
                ok { click(param("name")).wait() }
                miss { click("搜索"); click(param("name")).wait() }
            }
            label("done")
            finish("完成")
        }

        assertEquals("demo_script", def.id)
        assertTrue(def.phases.values.any { it.kind is PhaseKind.CapturePageTexts })
        assertTrue(def.phases.values.any { it.kind is PhaseKind.AskLlm })
        assertTrue(def.phases.containsKey("done"))

        val params = ActionSetParams(mapOf("name" to "响", "appName" to "DemoApp"))
        val first = ActionSetFlowEngine.drainNextSteps(def, params, emptyList())
        assertTrue(first is ActionSetDrain.RunActions)
        assertEquals("open_app", (first as ActionSetDrain.RunActions).steps.first().action)

        val afterOpen = ActionSetFlowEngine.drainNextSteps(
            def,
            params,
            listOf(step("open_app", "DemoApp", success = true)),
        )
        assertTrue(afterOpen is ActionSetDrain.CapturePageTexts)
    }

    @Test
    fun findBranch_missGoesToSearchActions() {
        val def = actionScript("branch_demo", register = false) {
            require("name", from = ParamSource.INPUT_TEXT)
            find(param("name")) {
                ok { click(param("name")) }
                miss { click("搜索"); click(param("name")) }
            }
            finish("ok")
        }
        val params = ActionSetParams(mapOf("name" to "响"))
        val drain = ActionSetFlowEngine.drainNextSteps(
            def,
            params,
            listOf(step("find_on_page", "响", success = false)),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        assertEquals("搜索", (drain as ActionSetDrain.RunActions).steps.first().targetText)
    }

    @Test
    fun label_preservesPhaseIdForResolveActions() {
        val def = actionScript("label_demo", register = false) {
            require("message", from = ParamSource.MESSAGE)
            label("chat")
            type(param("message"))
            send()
            finish("done")
        }
        val steps = def.resolveActions("chat", ActionSetParams(mapOf("message" to "hi")))
        assertEquals("type", steps.first().action)
        assertEquals("hi", steps.first().inputText)
        assertTrue(steps.any { it.action == "send" })
    }

    private fun step(
        action: String,
        target: String? = null,
        success: Boolean,
    ) = AgentStepRecord(
        step = 1,
        action = AgentAction(action = action, targetText = target),
        result = ActionExecutionResult(success = success, summary = "ok"),
        pageDiff = "",
    )
}
