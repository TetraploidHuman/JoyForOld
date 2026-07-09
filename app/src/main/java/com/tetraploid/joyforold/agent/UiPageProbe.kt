package com.tetraploid.joyforold.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object UiPageProbe {
    fun buildSummary(root: AccessibilityNodeInfo): String {
        return PageObservation.capture(root).toCompactSummary()
    }

    fun windowScore(root: AccessibilityNodeInfo): Int {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val snapshot = PageObservation.capture(root)
        var score = (rect.width() * rect.height()).coerceAtLeast(0) / 10_000
        score += snapshot.clickables.size * 40
        score += snapshot.editables.size * 900
        score += snapshot.sendButtons.size * 700
        score += UiNodeHeuristics.chatKeywordHits(root) * 80
        return score
    }
}
