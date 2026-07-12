package com.tetraploid.joyforold.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentPlanParserTest {
    @Test
    fun parsePlan_readsActionsArray() {
        val json = JSONObject(
            """
            {
              "actions": [
                {"action":"click","target_text":"Yuki"},
                {"action":"type","input_text":"你好"}
              ]
            }
            """.trimIndent(),
        )
        val plan = AgentPlanParser.parsePlan(json)
        assertEquals(1, plan.size)
        assertEquals("click", plan[0].action)
    }

    @Test
    fun sanitize_limitsToOneStep() {
        val actions = listOf(
            AgentAction(action = "scroll_down"),
            AgentAction(action = "click", targetText = "A"),
            AgentAction(action = "click", targetText = "B"),
        )
        assertEquals(1, AgentPlanParser.sanitize(actions).size)
    }

    @Test
    fun sanitize_openAppMustBeSolo() {
        val json = JSONObject(
            """
            {
              "actions": [
                {"action":"open_app","target_text":"QQ"},
                {"action":"click","target_text":"Yuki"}
              ]
            }
            """.trimIndent(),
        )
        val plan = AgentPlanParser.parsePlan(json)
        assertEquals(1, plan.size)
        assertEquals("open_app", plan[0].action)
    }

    @Test
    fun formatPageSection_diffOnly_omitsFullSnapshot() {
        val section = AgentMessageCompactor.formatPageSection(
            pageContext = "full page body",
            pageDiff = "页面指纹未变",
            minimalPageContext = "当前为 QQ | 可点击 8",
            mode = PageContextMode.DIFF_ONLY,
        )
        assertFalse(section.contains("full page body"))
        assertTrue(section.contains("【当前页面】"))
        assertTrue(section.contains("页面无明显变化"))
    }
}
