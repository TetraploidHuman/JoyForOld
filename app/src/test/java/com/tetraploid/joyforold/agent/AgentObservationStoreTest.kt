package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentObservationStoreTest {
    @Test
    fun record_ringsByCapacity() {
        val store = AgentObservationStore(capacity = 3)
        repeat(5) { i ->
            store.record(step = i, snapshot = snapshot(fp = "fp$i"), diff = "d$i")
        }
        assertEquals(3, store.size)
        assertNull(store.get(0))
        assertNull(store.get(1))
        assertNotNull(store.get(4))
        assertEquals(4, store.latest()?.step)
    }

    @Test
    fun queryPage_filtersByKeyword() {
        val store = AgentObservationStore()
        store.record(
            step = 1,
            snapshot = snapshot(
                clickables = listOf("搜索", "通讯录", "发现"),
                texts = listOf("微信", "搜索"),
            ),
            diff = "first",
        )
        val hit = store.queryPage(keyword = "搜")
        assertTrue(hit.contains("搜索"))
        assertFalse(hit.contains("通讯录"))
    }

    @Test
    fun attachTree_thenQueryTreeCached() {
        val store = AgentObservationStore()
        store.record(step = 2, snapshot = snapshot(), diff = "d")
        assertNull(store.queryTreeCached())
        assertTrue(store.attachTreeToLatest("- [0] Button 搜索\n- [1] TextView 输入"))
        val cached = store.queryTreeCached(keyword = "搜索")
        assertNotNull(cached)
        assertTrue(cached!!.contains("搜索"))
        assertFalse(cached.contains("输入"))
    }

    @Test
    fun sameFingerprint_inheritsTree() {
        val store = AgentObservationStore()
        store.record(step = 1, snapshot = snapshot(fp = "same"), diff = "a")
        store.attachTreeToLatest("tree-body")
        store.record(step = 2, snapshot = snapshot(fp = "same"), diff = "b")
        assertEquals("tree-body", store.latest()?.treeSnippet)
        assertNotNull(store.queryTreeCached())
    }

    @Test
    fun formatPromptHint_mentionsQueryTools() {
        val store = AgentObservationStore()
        assertEquals("", store.formatPromptHint())
        store.record(step = 3, snapshot = snapshot(), diff = "d")
        val hint = store.formatPromptHint()
        assertTrue(hint.contains("本地观察仓"))
        assertTrue(hint.contains("query_page"))
        assertTrue(hint.contains("step=3"))
    }

    @Test
    fun queryDiff_andEmptyStore() {
        val store = AgentObservationStore()
        assertTrue(store.queryDiff().contains("观察仓为空"))
        store.record(step = 1, snapshot = snapshot(), diff = "新增可点击: Foo")
        assertTrue(store.queryDiff().contains("Foo"))
    }

    private fun snapshot(
        fp: String = "fp",
        clickables: List<String> = listOf("按钮A"),
        texts: List<String> = listOf("文字A"),
    ): StructuredPageSnapshot = StructuredPageSnapshot(
        packageName = "com.tencent.mm",
        appHint = "",
        clickables = clickables,
        editables = emptyList(),
        visibleTexts = texts,
        sendButtons = emptyList(),
        fingerprint = fp,
    )
}
