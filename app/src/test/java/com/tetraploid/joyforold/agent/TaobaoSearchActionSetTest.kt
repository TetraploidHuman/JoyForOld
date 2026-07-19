package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.actionsets.TaobaoSearchActionSet
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_ASK_LLM
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_CAPTURE_PAGE_TEXTS
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetDrain
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetParams
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetRegistry
import com.tetraploid.joyforold.agent.actionsets.dsl.PhaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaobaoSearchActionSetTest {

    private fun searchMatch(query: String = "一加手机") = AgentActionSet.Match(
        id = AgentActionSet.ID_TAOBAO_SEARCH,
        initialParams = ActionSetParams(mapOf("query" to query)),
    )

    private fun searchOpenMatch(
        query: String = "一加手机",
        product: String = "",
    ) = AgentActionSet.Match(
        id = AgentActionSet.ID_TAOBAO_SEARCH_OPEN,
        initialParams = ActionSetParams(
            buildMap {
                put("query", query)
                if (product.isNotBlank()) put("product", product)
            },
        ),
    )

    private fun askPhaseId(): String =
        TaobaoSearchActionSet.searchAndOpen.phases.values
            .first { it.kind is PhaseKind.AskLlm }
            .id

    @Test
    fun registry_containsBothTaobaoSets() {
        assertEquals("taobao_search", TaobaoSearchActionSet.searchOnly.id)
        assertEquals("taobao_search_open", TaobaoSearchActionSet.searchAndOpen.id)
        assertNotNull(ActionSetRegistry.get(AgentActionSet.ID_TAOBAO_SEARCH))
        assertNotNull(ActionSetRegistry.get(AgentActionSet.ID_TAOBAO_SEARCH_OPEN))
    }

    @Test
    fun fromRunActionSetAction_parsesSearchOnly() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_TAOBAO_SEARCH,
            inputText = "一加手机",
        )
        val match = AgentActionSet.fromRunActionSetAction(action)
        assertNotNull(match)
        assertEquals(AgentActionSet.ID_TAOBAO_SEARCH, match!!.id)
        assertEquals("一加手机", match.params["query"])
    }

    @Test
    fun fromRunActionSetAction_parsesSearchOpen() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_TAOBAO_SEARCH_OPEN,
            inputText = "索尼手机",
        )
        val match = AgentActionSet.fromRunActionSetAction(action)
        assertNotNull(match)
        assertEquals(AgentActionSet.ID_TAOBAO_SEARCH_OPEN, match!!.id)
        assertEquals("索尼手机", match.params["query"])
    }

    @Test
    fun fromRunActionSetAction_nullWhenQueryMissing() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_TAOBAO_SEARCH,
        )
        assertNull(AgentActionSet.fromRunActionSetAction(action))
    }

    @Test
    fun searchOnly_firstDrain_opensTaobao() {
        val drain = AgentActionSet.drainNextSteps(searchMatch(), emptyList())
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertEquals("open_app", steps.first().action)
        assertEquals(TaobaoSearchActionSet.TAOBAO_APP, steps.first().targetText)
    }

    @Test
    fun searchOnly_enterSearchDoor_whenAlreadyOnDoor() {
        val drain = AgentActionSet.drainNextSteps(
            searchMatch(),
            listOf(step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true)),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val probe = (drain as ActionSetDrain.RunActions).steps.first()
        assertEquals("find_on_page", probe.action)
        assertEquals(TaobaoSearchActionSet.SEARCH_DOOR_MARKER, probe.targetText)
    }

    @Test
    fun searchOnly_typesAndClicksSearch_afterDoorReady() {
        val drain = AgentActionSet.drainNextSteps(
            searchMatch("一加15"),
            listOf(
                step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true),
                step("find_on_page", TaobaoSearchActionSet.SEARCH_DOOR_MARKER, success = true),
                step("wait", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertTrue(steps.any { it.action == "type" && it.inputText == "一加15" })
        assertTrue(steps.any { it.action == "click" && it.targetText == TaobaoSearchActionSet.SEARCH_BUTTON })
        assertEquals("finish", steps.last().action)
        assertTrue(steps.last().message!!.contains("一加15"))
    }

    @Test
    fun searchOnly_missDoor_clicksHomeSearchEntry() {
        val drain = AgentActionSet.drainNextSteps(
            searchMatch(),
            listOf(
                step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true),
                step("find_on_page", TaobaoSearchActionSet.SEARCH_DOOR_MARKER, success = false),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertEquals(TaobaoSearchActionSet.HOME_SEARCH_ENTRY, steps.first().targetText)
    }

    @Test
    fun searchOpen_afterSearch_capturesThenAskLlm() {
        val afterSearch = listOf(
            step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true),
            step("find_on_page", TaobaoSearchActionSet.SEARCH_DOOR_MARKER, success = true),
            step("wait", success = true),
            step("type", input = "一加手机", success = true),
            step("wait", success = true),
            step("click", TaobaoSearchActionSet.SEARCH_BUTTON, success = true),
            step("wait", success = true),
        )
        val capture = AgentActionSet.drainNextSteps(searchOpenMatch(), afterSearch)
        assertTrue(capture is ActionSetDrain.CapturePageTexts)

        val afterCapture = afterSearch + step(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true)
        val ask = AgentActionSet.drainNextSteps(searchOpenMatch(), afterCapture)
        assertTrue(ask is ActionSetDrain.AskLlm)
        val askDrain = ask as ActionSetDrain.AskLlm
        assertTrue(askDrain.userPrompt.contains("一加手机"))
        assertTrue(askDrain.writeFields.contains("product"))
        assertEquals(askPhaseId(), askDrain.phaseId)
    }

    @Test
    fun searchOpen_clicksProduct_afterAskLlm() {
        val product = "一加 Turbo 6V 手机 新品上市 2182.81元 8000+人付款"
        val match = searchOpenMatch(product = product)
        match.updateParams(mapOf("product" to product, "candidates" to "$product|其它"))
        val records = listOf(
            step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true),
            step("find_on_page", TaobaoSearchActionSet.SEARCH_DOOR_MARKER, success = true),
            step("wait", success = true),
            step("type", input = "一加手机", success = true),
            step("wait", success = true),
            step("click", TaobaoSearchActionSet.SEARCH_BUTTON, success = true),
            step("wait", success = true),
            step(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            step(ACTION_ASK_LLM, askPhaseId(), success = true),
        )
        val drain = AgentActionSet.drainNextSteps(match, records)
        assertTrue(drain is ActionSetDrain.RunActions)
        val probe = (drain as ActionSetDrain.RunActions).steps.first()
        assertEquals("find_on_page", probe.action)
        assertEquals(product, probe.targetText)

        val afterFind = records + step("find_on_page", product, success = true)
        val clickDrain = AgentActionSet.drainNextSteps(match, afterFind)
        assertTrue(clickDrain is ActionSetDrain.RunActions)
        val clickSteps = (clickDrain as ActionSetDrain.RunActions).steps
        assertEquals("click", clickSteps.first().action)
        assertEquals(product, clickSteps.first().targetText)

        val afterClick = afterFind + listOf(
            step("click", product, success = true),
            step("wait", success = true),
        )
        val finishDrain = AgentActionSet.drainNextSteps(match, afterClick)
        assertTrue(finishDrain is ActionSetDrain.RunActions)
        val finishSteps = (finishDrain as ActionSetDrain.RunActions).steps
        assertTrue(finishSteps.any { it.action == "finish" && it.message!!.contains("打开商品") })
    }

    @Test
    fun searchOpen_missProduct_fallsBackToQueryClick() {
        val product = "一加 Turbo 6V 手机 新品上市 2182.81元 8000+人付款"
        val match = searchOpenMatch(query = "一加手机", product = product)
        match.updateParams(mapOf("product" to product))
        val records = listOf(
            step("open_app", TaobaoSearchActionSet.TAOBAO_APP, success = true),
            step("find_on_page", TaobaoSearchActionSet.SEARCH_DOOR_MARKER, success = true),
            step("wait", success = true),
            step("type", input = "一加手机", success = true),
            step("wait", success = true),
            step("click", TaobaoSearchActionSet.SEARCH_BUTTON, success = true),
            step("wait", success = true),
            step(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            step(ACTION_ASK_LLM, askPhaseId(), success = true),
            step("find_on_page", product, success = false),
        )
        val drain = AgentActionSet.drainNextSteps(match, records)
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertEquals("click", steps.first().action)
        assertEquals("一加手机", steps.first().targetText)
    }

    @Test
    fun uiLabel_searchOnly() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_TAOBAO_SEARCH,
            inputText = "手机",
        )
        assertEquals("动作组：淘宝搜索「手机」", AgentActionSet.uiLabel(action))
    }

    @Test
    fun descriptionsForPrompt_listsTaobaoIds() {
        val text = AgentActionSet.descriptionsForPrompt()
        assertTrue(text.contains(AgentActionSet.ID_TAOBAO_SEARCH))
        assertTrue(text.contains(AgentActionSet.ID_TAOBAO_SEARCH_OPEN))
        assertTrue(text.contains(AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE))
    }

    private fun step(
        action: String,
        target: String? = null,
        input: String? = null,
        success: Boolean,
    ) = AgentStepRecord(
        step = 1,
        action = AgentAction(action = action, targetText = target, inputText = input),
        result = ActionExecutionResult(success = success, summary = "ok"),
        pageDiff = "",
    )
}
