package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTargetNormalizerTest {
    @Test
    fun normalize_stripsFindOnPageAnnotationsAndGluedViewId() {
        assertEquals("好想来", ClickTargetNormalizer.normalize("好想来search_fake_text [可点击]"))
        assertEquals("搜索", ClickTargetNormalizer.normalize("搜索 [可点击]"))
    }

    @Test
    fun clickCandidates_prefersNormalizedLabel() {
        val candidates = ClickTargetNormalizer.clickCandidates("好想来search_fake_text [可点击]")
        assertTrue(candidates.first() == "好想来")
    }
}
