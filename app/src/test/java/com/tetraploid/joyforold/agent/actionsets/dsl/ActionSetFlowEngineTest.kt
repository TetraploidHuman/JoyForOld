package com.tetraploid.joyforold.agent.actionsets.dsl

import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentStepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSetFlowEngineTest {

    private val definition = actionSet("demo") {
        params {
            required("name", from = ParamSource.INPUT_TEXT)
            optional("candidates", default = "")
        }
        flow {
            phase("open") {
                openApp("DemoApp")
                waitUi()
            }
            phase("collect") {
                capturePageTexts(into = "candidates")
            }
            phase("resolve") {
                askLlm(writeTo = listOf("name")) {
                    system("match name")
                    user { p -> "utterance=${p["name"]} cands=${p["candidates"]}" }
                }
            }
            phase("probe") {
                findOnPage(param("name"))
                onSuccess("direct")
                onFail("fallback")
            }
            phase("direct") {
                click(param("name"))
                waitUi()
                goto("done")
            }
            phase("fallback") {
                click("搜索")
                type(text = param("name"), target = "搜索框")
                waitUi()
                click(param("name"))
                waitUi()
                goto("done")
            }
            phase("done") {
                finish("完成")
            }
        }
    }

    private val params = ActionSetParams(mapOf("name" to "小明"))

    @Test
    fun drain_startsAtFirstPhase() {
        val drain = ActionSetFlowEngine.drainNextSteps(definition, params, emptyList())
        assertTrue(drain is ActionSetDrain.RunActions)
        val next = (drain as ActionSetDrain.RunActions).steps
        assertEquals("open_app", next.first().action)
        assertTrue(next.any { it.action == "wait" })
    }

    @Test
    fun drain_advancesPastOpenWithoutWaitRecord() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(record("open_app", "DemoApp", success = true)),
        )
        assertTrue(drain is ActionSetDrain.CapturePageTexts)
    }

    @Test
    fun drain_advancesPastOpenWithWaitRecord() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record("wait", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.CapturePageTexts)
        assertEquals("candidates", (drain as ActionSetDrain.CapturePageTexts).intoParam)
    }

    @Test
    fun drain_captureAfterOpen() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(record("open_app", "DemoApp", success = true)),
        )
        assertTrue(drain is ActionSetDrain.CapturePageTexts)
        assertEquals("candidates", (drain as ActionSetDrain.CapturePageTexts).intoParam)
    }

    @Test
    fun drain_askLlmAfterCapture() {
        val withCandidates = ActionSetParams(mapOf("name" to "小明", "candidates" to "小民|晓明"))
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            withCandidates,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.AskLlm)
        val ask = drain as ActionSetDrain.AskLlm
        assertEquals(listOf("name"), ask.writeFields)
        assertTrue(ask.userPrompt.contains("小民|晓明"))
        assertEquals("match name", ask.systemPrompt)
    }

    @Test
    fun drain_retriesAskLlmWhenMarkerFailed() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = false),
            ),
        )
        assertTrue(drain is ActionSetDrain.AskLlm)
    }

    @Test
    fun drain_onFailGoesToFallback_afterAskLlm() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = true),
                record("find_on_page", "小明", success = false),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val next = (drain as ActionSetDrain.RunActions).steps
        assertEquals("click", next.first().action)
        assertEquals("搜索", next.first().targetText)
    }

    @Test
    fun drain_onSuccessGoesToDirect() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = true),
                record("find_on_page", "小明", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val next = (drain as ActionSetDrain.RunActions).steps
        assertEquals("click", next.first().action)
        assertEquals("小明", next.first().targetText)
    }

    @Test
    fun drain_usesUpdatedParamsAfterAskLlm() {
        val updated = ActionSetParams(mapOf("name" to "晓明", "candidates" to "晓明|小民"))
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            updated,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        assertEquals("晓明", (drain as ActionSetDrain.RunActions).steps.first().targetText)
    }

    @Test
    fun drain_resumesMidFallbackPhase() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = true),
                record("find_on_page", "小明", success = false),
                record("click", "搜索", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val next = (drain as ActionSetDrain.RunActions).steps
        assertEquals("type", next.first().action)
        assertEquals("小明", next.first().inputText)
    }

    @Test
    fun drain_chatAfterDirectOpen() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(
                record("open_app", "DemoApp", success = true),
                record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                record(ACTION_ASK_LLM, "resolve", success = true),
                record("find_on_page", "小明", success = true),
                record("click", "小明", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        assertEquals("finish", (drain as ActionSetDrain.RunActions).steps.last().action)
    }

    @Test
    fun drain_doneAfterFinish() {
        val drain = ActionSetFlowEngine.drainNextSteps(
            definition,
            params,
            listOf(record("finish", message = "完成", success = true)),
        )
        assertTrue(drain is ActionSetDrain.Done)
        assertTrue(
            ActionSetFlowEngine.isCompleted(
                definition,
                params,
                listOf(
                    record("open_app", "DemoApp", success = true),
                    record(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                    record(ACTION_ASK_LLM, "resolve", success = true),
                    record("find_on_page", "小明", success = true),
                    record("click", "小明", success = true),
                    record("finish", message = "完成", success = true),
                ),
            ),
        )
        assertFalse(
            ActionSetFlowEngine.isCompleted(
                definition,
                params,
                listOf(record("open_app", "DemoApp", success = true)),
            ),
        )
    }

    @Test
    fun allStepsHappyPath_skipsCaptureAndAskLlm() {
        val steps = ActionSetFlowEngine.allStepsHappyPath(definition, params)
        assertTrue(steps.any { it.action == "find_on_page" })
        assertTrue(steps.none { it.action == ACTION_CAPTURE_PAGE_TEXTS })
        assertTrue(steps.none { it.action == ACTION_ASK_LLM })
        assertTrue(steps.none { it.targetText == "搜索" })
        assertEquals("finish", steps.last().action)
    }

    @Test
    fun drain_failedClickDoesNotRequeueSameStep() {
        val linear = actionSet("linear_click") {
            params {
                required("q", from = ParamSource.INPUT_TEXT)
            }
            flow {
                phase("tap") {
                    click("路线")
                    waitUi()
                }
                phase("done") {
                    finish("完成")
                }
            }
        }
        val drain = ActionSetFlowEngine.drainNextSteps(
            linear,
            ActionSetParams(mapOf("q" to "肯德基")),
            listOf(record("click", "路线", success = false)),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val next = (drain as ActionSetDrain.RunActions).steps
        assertEquals("finish", next.first().action)
        assertTrue(next.none { it.action == "click" })
    }

    @Test
    fun actionsMatch_openAppPartialTarget() {
        assertTrue(
            ActionSetFlowEngine.actionsMatch(
                AgentAction(action = "open_app", targetText = "微信"),
                AgentAction(action = "open_app", targetText = "微信微信"),
            ),
        )
    }

    private fun record(
        action: String,
        target: String? = null,
        input: String? = null,
        message: String? = null,
        success: Boolean,
    ) = AgentStepRecord(
        step = 1,
        action = AgentAction(action = action, targetText = target, inputText = input, message = message),
        result = ActionExecutionResult(success = success, summary = "ok"),
        pageDiff = "",
    )
}
