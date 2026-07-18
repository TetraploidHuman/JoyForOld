package com.tetraploid.joyforold.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTargetScorerTest {
    private fun candidate(
        query: String,
        label: String,
        top: Int,
        height: Int = 160,
        width: Int = 900,
    ) = ClickTargetScorer.Candidate(
        query = query,
        nodeText = label,
        displayLabel = label,
        left = 40,
        top = top,
        right = 40 + width,
        bottom = top + height,
        visibleToUser = true,
        screenWidth = 1080,
        screenHeight = 2400,
    )

    @Test
    fun contact_prefersExactNameOverPartialMatchAbove() {
        val query = "张三"
        val wrongAbove = candidate(query, "张三丰", top = 400)
        val exactBelow = candidate(query, "张三", top = 700)
        assertTrue(
            ClickTargetScorer.score(exactBelow) > ClickTargetScorer.score(wrongAbove),
        )
    }

    @Test
    fun contact_prefersExactOverLongerNameContainingQuery() {
        val query = "妈妈"
        val group = candidate(query, "妈妈的群", top = 300)
        val person = candidate(query, "妈妈", top = 900)
        assertTrue(ClickTargetScorer.score(person) > ClickTargetScorer.score(group))
    }

    @Test
    fun contact_prefersListRowOverHugeContainer() {
        val query = "大女儿"
        val row = candidate(query, "大女儿", top = 800, height = 160, width = 1000)
        val huge = candidate(query, "大女儿", top = 200, height = 2000, width = 1080)
        assertTrue(ClickTargetScorer.score(row) > ClickTargetScorer.score(huge))
    }

    @Test
    fun click_doesNotPreferTopJustBecauseNearerOnScreen() {
        // 「最近」由 AmapPoiResolver Web API 决定，不在 click 打分里用 Y 坐标冒充
        val query = "肯德基(郴州同心路店)"
        val exactLower = candidate(query, "肯德基(郴州同心路店)", top = 900)
        val otherBrandUpper = candidate(query, "肯德基(振兴之门店)", top = 400)
        assertTrue(ClickTargetScorer.score(exactLower) > ClickTargetScorer.score(otherBrandUpper))
    }
}
