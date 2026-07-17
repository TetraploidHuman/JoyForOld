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
class TaskPhasePlannerTest {
    @Test
    fun plan_sendMessageInQq_splitsIntoThreePhases() {
        val phases = TaskPhasePlanner.planFromCommand("打开QQ给yuki发你好呀")

        assertEquals(3, phases.size)
        assertEquals("打开QQ", phases[0].label)
        assertEquals("找到yuki", phases[1].label)
        assertTrue(phases[2].label.contains("你好呀"))
    }

    @Test
    fun plan_sendToFamily_usesReadablePhases() {
        val phases = TaskPhasePlanner.planFromCommand("给yuki发消息：你好呀")

        assertTrue(phases.any { it.label.contains("找到yuki") })
        assertTrue(phases.any { it.label.contains("你好呀") })
    }

    @Test
    fun plan_openAppCategoryPrefix_usesAppName() {
        val phases = TaskPhasePlanner.planFromCommand("打开应用 微信")

        assertEquals(1, phases.size)
        assertEquals("打开微信", phases[0].label)
    }

    @Test
    fun plan_openWechat_singlePhase() {
        val phases = TaskPhasePlanner.planFromCommand("打开微信")

        assertEquals(1, phases.size)
        assertEquals("打开微信", phases[0].label)
    }

    @Test
    fun plan_openSoftware_noGenericFallback() {
        val phases = TaskPhasePlanner.planFromCommand("打开软件")

        assertEquals(1, phases.size)
        assertEquals("打开软件", phases[0].label)
        assertFalse(phases.any { it.label.contains("理解指令") })
    }

    @Test
    fun plan_fromTemplate_sendToFamily() {
        val phases = TaskPhasePlanner.planFromCommand("发送消息给家人")

        assertEquals(1, phases.size)
        assertEquals("打开微信", phases[0].label)
    }

    @Test
    fun plan_unknownCommand_usesCommandSummaryNotGenericSteps() {
        val phases = TaskPhasePlanner.planFromCommand("帮我把屏幕调亮一点")

        assertEquals(1, phases.size)
        assertEquals("帮我把屏幕调亮一点", phases[0].label)
        assertFalse(phases.any { it.label.contains("理解指令") })
    }

    @Test
    fun advance_openApp_completesOpenPhase() {
        val phases = TaskPhasePlanner.planFromCommand("打开QQ给yuki发你好呀")
        val updated = TaskPhaseTracker.advanceFromAction(phases, "open_app")

        assertEquals(TaskStepStatus.Completed, updated[0].status)
        assertEquals(TaskStepStatus.InProgress, updated[1].status)
    }

    @Test
    fun parseFromLlmJson_readsPhasesArray() {
        val json = JSONObject(
            """
            {"phases":["打开微信","找到小明","发送消息"]}
            """.trimIndent(),
        )
        val phases = TaskPhasePlanner.parseFromLlmJson(json)
        assertEquals(3, phases.size)
        assertEquals("打开微信", phases[0].label)
        assertEquals(TaskStepStatus.InProgress, phases[0].status)
        assertEquals(TaskStepStatus.Pending, phases[1].status)
    }

    @Test
    fun parseFromLlmJson_readsObjectLabels() {
        val json = JSONObject(
            """
            {"phases":[{"label":"打开设置"},{"title":"打开 WLAN"}]}
            """.trimIndent(),
        )
        val phases = TaskPhasePlanner.parseFromLlmJson(json)
        assertEquals(2, phases.size)
        assertEquals("打开设置", phases[0].label)
        assertEquals("打开 WLAN", phases[1].label)
    }

    @Test
    fun fromLabels_dropsFinishLikePhases() {
        val phases = TaskPhasePlanner.fromLabels(
            listOf("播放后结束任务", "打开哔哩哔哩", "搜索假面骑士", "进入并播放视频"),
        )
        assertEquals(3, phases.size)
        assertEquals("打开哔哩哔哩", phases[0].label)
        assertEquals(TaskStepStatus.InProgress, phases[0].status)
        assertFalse(phases.any { TaskPhasePlanner.isFinishLikePhase(it.label) })
    }
}
