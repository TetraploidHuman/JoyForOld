package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.actionsets.ImSendMessageActionSet
import com.tetraploid.joyforold.agent.actionsets.MapNavigateActionSet
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_ASK_LLM
import com.tetraploid.joyforold.agent.actionsets.dsl.ACTION_CAPTURE_PAGE_TEXTS
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetDrain
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetParams
import com.tetraploid.joyforold.agent.actionsets.dsl.ActionSetRegistry
import com.tetraploid.joyforold.agent.actionsets.dsl.PhaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionSetTest {
    private fun sendImMatch(
        contact: String = "响",
        message: String = "到家了",
    ) = AgentActionSet.Match(
        id = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
        initialParams = ActionSetParams(
            mapOf(
                "contact" to contact,
                "message" to message,
            ),
        ),
    )

    /** open → capture → askLlm 已完成的记录前缀。 */
    private fun afterResolveRecords() = listOf(
        stepRecord("open_app", "微信", success = true),
        stepRecord(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
        stepRecord(ACTION_ASK_LLM, sendImAskPhaseId(), success = true),
    )

    private fun sendImAskPhaseId(): String =
        ImSendMessageActionSet.definition.phases.values
            .first { it.kind is PhaseKind.AskLlm }
            .id

    @Test
    fun registry_containsSendImMessage() {
        // 触发 object init
        assertEquals("wechat_send_im_message", ImSendMessageActionSet.definition.id)
        assertNotNull(ActionSetRegistry.get(AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE))
    }

    @Test
    fun registry_containsMapNavigate() {
        assertEquals(AgentActionSet.ID_MAP_NAVIGATE, MapNavigateActionSet.definition.id)
        assertNotNull(ActionSetRegistry.get(AgentActionSet.ID_MAP_NAVIGATE))
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_MAP_NAVIGATE,
            inputText = "肯德基",
        )
        val match = AgentActionSet.fromRunActionSetAction(action)
        assertNotNull(match)
        assertEquals("肯德基", match!!.params["query"])
    }

    @Test
    fun mapNavigate_onRouteMiss_clicksSelectedPoiNotHardcodedStoreSuffix() {
        val match = AgentActionSet.Match(
            id = AgentActionSet.ID_MAP_NAVIGATE,
            initialParams = ActionSetParams(
                mapOf(
                    "query" to "公园",
                    "candidates" to "人民公园 | 返回 | 搜索",
                    "poi" to "人民公园",
                ),
            ),
        )
        val afterSelect = listOf(
            stepRecord("navigate_to", "公园", success = true),
            stepRecord("wait", success = true),
            stepRecord("wait", success = true),
            stepRecord(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            stepRecord(ACTION_ASK_LLM, mapNavigateAskPhaseId(), success = true),
            stepRecord("find_on_page", "路线", success = false),
        )
        val drain = AgentActionSet.drainNextSteps(match, afterSelect)
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertTrue(steps.any { it.action == "click" && it.targetText == "人民公园" })
        assertTrue(steps.none { it.action == "click" && it.targetText == "店)" })
    }

    @Test
    fun mapNavigate_afterPoi_prefersStartNavThenNavButton() {
        val match = AgentActionSet.Match(
            id = AgentActionSet.ID_MAP_NAVIGATE,
            initialParams = ActionSetParams(
                mapOf(
                    "query" to "肯德基",
                    "poi" to "肯德基(桂阳向阳路店)",
                ),
            ),
        )
        val afterPoi = listOf(
            stepRecord("navigate_to", "肯德基", success = true),
            stepRecord("wait", success = true),
            stepRecord("wait", success = true),
            stepRecord(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            stepRecord(ACTION_ASK_LLM, mapNavigateAskPhaseId(), success = true),
            stepRecord("find_on_page", "路线", success = false),
            stepRecord("click", "肯德基(桂阳向阳路店)", success = true),
            stepRecord("wait", success = true),
        )
        val drain = AgentActionSet.drainNextSteps(match, afterPoi)
        assertTrue(drain is ActionSetDrain.RunActions)
        val steps = (drain as ActionSetDrain.RunActions).steps
        assertTrue(steps.any { it.action == "find_on_page" && it.targetText == MapNavigateActionSet.START_NAV })
        assertTrue(steps.none { it.action == "click" && it.targetText == MapNavigateActionSet.ROUTE_BUTTON })
    }

    private fun mapNavigateAskPhaseId(): String =
        MapNavigateActionSet.definition.phases.values
            .first { it.kind is PhaseKind.AskLlm }
            .id


    @Test
    fun fromRunActionSetAction_parsesSendImMessage() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            inputText = "响",
            message = "到家了",
        )
        val actionSet = AgentActionSet.fromRunActionSetAction(action)
        assertNotNull(actionSet)
        assertEquals(AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE, actionSet!!.id)
        assertEquals("响", actionSet.params["contact"])
        assertEquals("到家了", actionSet.params["message"])
        assertFalse(actionSet.params.values.containsKey("appName"))
    }

    @Test
    fun fromRunActionSetAction_nullWhenParamsMissing() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            inputText = "响",
        )
        assertNull(AgentActionSet.fromRunActionSetAction(action))
    }

    @Test
    fun fromRunActionSetAction_nullWhenUnknownId() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = "not_registered",
            inputText = "响",
            message = "hi",
        )
        assertNull(AgentActionSet.fromRunActionSetAction(action))
    }

    @Test
    fun expandPlannedActions_activatesActionSetWithoutInliningAllSteps() {
        val runActionSet = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            inputText = "大女儿",
            message = "今晚回家吃饭",
        )
        val expanded = AgentActionSet.expandPlannedActions(listOf(runActionSet))
        assertNotNull(expanded.activeActionSet)
        assertTrue(expanded.steps.isEmpty())
    }

    @Test
    fun expandPlannedActions_keepsNonActionSetSteps() {
        val wait = AgentAction(action = "wait")
        val run = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            inputText = "响",
            message = "到家了",
        )
        val expanded = AgentActionSet.expandPlannedActions(listOf(wait, run))
        assertEquals(1, expanded.steps.size)
        assertEquals("wait", expanded.steps.first().action)
        assertNotNull(expanded.activeActionSet)
    }

    @Test
    fun allSteps_usesAccessibilityActions_andSkipsCaptureAsk() {
        val steps = AgentActionSet.allSteps(sendImMatch())
        assertEquals("open_app", steps.first().action)
        assertTrue(steps.none { it.action == ACTION_CAPTURE_PAGE_TEXTS })
        assertTrue(steps.none { it.action == ACTION_ASK_LLM })
        assertTrue(steps.any { it.action == "find_on_page" && it.targetText == "响" })
        assertTrue(steps.any { it.action == "click" && it.targetText == "响" })
        assertTrue(steps.any { it.action == "type" && it.inputText == "到家了" })
        assertTrue(steps.any { it.action == "send" })
        assertTrue(steps.none { it.action == "tap" })
        assertEquals("finish", steps.last().action)
    }

    @Test
    fun drainNextSteps_fullHappyPathProgression() {
        val match = sendImMatch()
        assertTrue(
            AgentActionSet.drainNextSteps(match, emptyList()) is ActionSetDrain.RunActions,
        )
        assertTrue(
            AgentActionSet.drainNextSteps(
                match,
                listOf(stepRecord("open_app", "微信", success = true)),
            ) is ActionSetDrain.CapturePageTexts,
        )
        assertTrue(
            AgentActionSet.drainNextSteps(
                match,
                listOf(
                    stepRecord("open_app", "微信", success = true),
                    stepRecord(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
                ),
            ) is ActionSetDrain.AskLlm,
        )
        val probe = AgentActionSet.drainNextSteps(match, afterResolveRecords())
        assertTrue(probe is ActionSetDrain.RunActions)
        assertEquals("find_on_page", (probe as ActionSetDrain.RunActions).steps.first().action)

        val openFromList = AgentActionSet.drainNextSteps(
            match,
            afterResolveRecords() + listOf(stepRecord("find_on_page", "响", success = true)),
        )
        assertEquals("click", (openFromList as ActionSetDrain.RunActions).steps.first().action)

        val chat = AgentActionSet.drainNextSteps(
            match,
            afterResolveRecords() + listOf(
                stepRecord("find_on_page", "响", success = true),
                stepRecord("click", "响", success = true),
            ),
        )
        val chatSteps = (chat as ActionSetDrain.RunActions).steps
        assertTrue(chatSteps.any { it.action == "type" && it.inputText == "到家了" })
        assertTrue(chatSteps.any { it.action == "send" })
        assertEquals("finish", chatSteps.last().action)
    }

    @Test
    fun drainNextSteps_fallsBackToSearchWhenListMisses() {
        val drain = AgentActionSet.drainNextSteps(
            actionSet = sendImMatch(),
            stepRecords = afterResolveRecords() + listOf(
                stepRecord("find_on_page", "响", success = false),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val planned = (drain as ActionSetDrain.RunActions).steps
        assertEquals("click", planned.first().action)
        assertEquals(ImSendMessageActionSet.SEARCH_ENTRY_LABEL, planned.first().targetText)
        assertTrue(
            planned.any {
                it.action == "type" &&
                    it.inputText == "响" &&
                    it.targetText == ImSendMessageActionSet.SEARCH_FIELD_HINT
            },
        )
    }

    @Test
    fun drainNextSteps_searchPathReachesChat() {
        val drain = AgentActionSet.drainNextSteps(
            actionSet = sendImMatch(),
            stepRecords = afterResolveRecords() + listOf(
                stepRecord("find_on_page", "响", success = false),
                stepRecord("click", ImSendMessageActionSet.SEARCH_ENTRY_LABEL, success = true),
                stepRecord(
                    "type",
                    target = ImSendMessageActionSet.SEARCH_FIELD_HINT,
                    input = "响",
                    success = true,
                ),
                stepRecord("click", "响", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.RunActions)
        val planned = (drain as ActionSetDrain.RunActions).steps
        assertTrue(planned.any { it.action == "type" && it.inputText == "到家了" })
        assertTrue(planned.any { it.action == "send" })
    }

    @Test
    fun drainNextSteps_doneWhenActionSetCompleted() {
        val drain = AgentActionSet.drainNextSteps(
            actionSet = sendImMatch(),
            stepRecords = listOf(
                stepRecord(
                    "finish",
                    message = "已尝试通过微信发送：到家了",
                    success = true,
                ),
            ),
        )
        assertTrue(drain is ActionSetDrain.Done)
        assertTrue(
            AgentActionSet.isActionSetCompleted(
                sendImMatch(),
                listOf(
                    stepRecord(
                        "finish",
                        message = "已尝试通过微信发送：到家了",
                        success = true,
                    ),
                ),
            ),
        )
        assertFalse(AgentActionSet.isActionSetCompleted(sendImMatch(), emptyList()))
    }

    @Test
    fun uiLabel_showsActionGroupChinese() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            inputText = "响",
            message = "到家了",
        )
        assertEquals("动作组：微信给响发消息", AgentActionSet.uiLabel(action))
    }

    @Test
    fun uiLabel_fallbackWithoutContact() {
        val action = AgentAction(
            action = AgentActionSet.ACTION_RUN_ACTION_SET,
            targetText = AgentActionSet.ID_WECHAT_SEND_IM_MESSAGE,
            message = "到家了",
        )
        // contact 缺失时 resolveParams 为 null → 空 params → 默认文案
        assertEquals("动作组：微信发消息", AgentActionSet.uiLabel(action))
    }

    @Test
    fun match_updateParams_rewritesContact_forProbe() {
        val match = sendImMatch(contact = "王小明")
        match.updateParams(mapOf("contact" to "王晓明", "candidates" to "王晓明|张三"))
        assertEquals("王晓明", match.params["contact"])
        val drain = AgentActionSet.drainNextSteps(
            actionSet = match,
            stepRecords = afterResolveRecords(),
        )
        assertEquals(
            "王晓明",
            (drain as ActionSetDrain.RunActions).steps.first().targetText,
        )
    }

    @Test
    fun stepsOnOpenChatPage_typesAndSends() {
        val steps = AgentActionSet.stepsOnOpenChatPage("你好")
        assertTrue(steps.any { it.action == "type" && it.inputText == "你好" })
        assertTrue(steps.any { it.action == "send" })
        assertEquals("finish", steps.last().action)
        assertTrue(steps.last().message!!.contains("你好"))
        assertTrue(steps.last().message!!.contains("微信"))
    }

    @Test
    fun askLlmDrain_includesCandidatesInUserPrompt() {
        val match = sendImMatch(contact = "王小明")
        match.updateParams(mapOf("candidates" to "王晓明|王小民"))
        val drain = AgentActionSet.drainNextSteps(
            match,
            listOf(
                stepRecord("open_app", "微信", success = true),
                stepRecord(ACTION_CAPTURE_PAGE_TEXTS, "candidates", success = true),
            ),
        )
        assertTrue(drain is ActionSetDrain.AskLlm)
        val ask = drain as ActionSetDrain.AskLlm
        assertTrue(ask.userPrompt.contains("王小明"))
        assertTrue(ask.userPrompt.contains("王晓明|王小民"))
        assertTrue(ask.writeFields.contains("contact"))
    }

    private fun stepRecord(
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
