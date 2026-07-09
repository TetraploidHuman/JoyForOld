package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentMemoryStoreTest {
    private lateinit var store: AgentMemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = AgentMemoryStore(context)
    }

    @Test
    fun selectRelevantMemories_matchesCurrentCommandOnly() {
        val memories = listOf(
            KeyMemory(
                id = "1",
                summary = "用户常用 QQ 给 Yuki 发消息",
                userCommand = "给 Yuki 发消息",
                outcome = "成功",
                createdAt = 1L,
                tags = listOf("发消息"),
            ),
            KeyMemory(
                id = "2",
                summary = "用户偏好系统电话拨打 610",
                userCommand = "给 610 打电话",
                outcome = "成功",
                createdAt = 2L,
                tags = listOf("打电话"),
            ),
        )

        val selected = store.selectRelevantMemories(memories, "打开设置")
        assertTrue(selected.isEmpty())

        val callSelected = store.selectRelevantMemories(memories, "给 610 打电话")
        assertTrue(callSelected.any { it.id == "2" })
        assertFalse(callSelected.any { it.id == "1" })
    }
}
