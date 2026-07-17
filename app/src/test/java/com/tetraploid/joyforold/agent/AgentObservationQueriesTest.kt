package com.tetraploid.joyforold.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentObservationQueriesTest {
    @Test
    fun execute_queryPage_withoutStore_failsGracefully() = runBlocking {
        val result = AgentObservationQueries.execute(
            action = AgentAction(action = AgentObservationQueries.ACTION_QUERY_PAGE, targetText = "搜"),
            store = null,
        )
        assertFalse(result.success)
        assertTrue(result.summary.contains("观察仓不可用"))
    }

    @Test
    fun execute_queryTree_liveFetchesAndCaches() = runBlocking {
        val store = AgentObservationStore()
        store.record(
            step = 1,
            snapshot = StructuredPageSnapshot(
                packageName = "com.tencent.mm",
                appHint = "",
                clickables = listOf("搜索"),
                editables = emptyList(),
                visibleTexts = emptyList(),
                sendButtons = emptyList(),
                fingerprint = "fp1",
            ),
            diff = "d",
        )
        var fetched = 0
        val first = AgentObservationQueries.execute(
            action = AgentAction(action = AgentObservationQueries.ACTION_QUERY_TREE, targetText = "按钮"),
            store = store,
            liveTreeFetcher = {
                fetched++
                ActionExecutionResult(
                    success = true,
                    summary = "已读取结构树片段",
                    detail = "- [0] Button 目标按钮\n- [1] TextView 输入",
                )
            },
        )
        assertTrue(first.success)
        assertEquals(1, fetched)
        assertTrue(first.detail.contains("目标按钮"))

        val second = AgentObservationQueries.execute(
            action = AgentAction(action = AgentObservationQueries.ACTION_QUERY_TREE, targetText = "按钮"),
            store = store,
            liveTreeFetcher = {
                fetched++
                error("should use cache")
            },
        )
        assertTrue(second.success)
        assertEquals(1, fetched)
    }

    @Test
    fun rememberReadTree_attachesToLatest() {
        val store = AgentObservationStore()
        store.record(
            step = 2,
            snapshot = StructuredPageSnapshot(
                packageName = "pkg",
                appHint = "",
                clickables = emptyList(),
                editables = emptyList(),
                visibleTexts = emptyList(),
                sendButtons = emptyList(),
                fingerprint = "x",
            ),
            diff = "",
        )
        AgentObservationQueries.rememberReadTree(
            store,
            ActionExecutionResult(true, "已读取结构树片段", detail = "TREE"),
        )
        assertEquals("TREE", store.latest()?.treeSnippet)
    }

    @Test
    fun isObservationQuery_coversAll() {
        AgentObservationQueries.allActionNames.forEach {
            assertTrue(AgentObservationQueries.isObservationQuery(it))
        }
        assertFalse(AgentObservationQueries.isObservationQuery("read_tree"))
    }
}
