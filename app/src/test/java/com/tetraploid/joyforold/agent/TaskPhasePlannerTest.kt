package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
