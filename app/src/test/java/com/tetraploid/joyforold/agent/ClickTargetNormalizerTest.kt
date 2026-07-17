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

    @Test
    fun stripMarkup_removesAmapHtmlStoreName() {
        val raw = "<font color='@Color_Text_Brand'>肯德基</font>(郴州同心路店)"
        assertEquals("肯德基(郴州同心路店)", ClickTargetNormalizer.stripMarkup(raw))
        assertEquals("肯德基(郴州同心路店)", ClickTargetNormalizer.normalize(raw))
    }

    @Test
    fun clickCandidates_includesShortBrandFromParenStoreName() {
        val candidates = ClickTargetNormalizer.clickCandidates("肯德基(桂阳向阳路店)")
        assertTrue(candidates.contains("肯德基(桂阳向阳路店)"))
        assertTrue(candidates.contains("肯德基"))
    }
}
