package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskScreenGuardTest {

    private fun snap(
        pkg: String = "com.tencent.mm",
        clickables: List<String> = emptyList(),
        texts: List<String> = emptyList(),
    ) = StructuredPageSnapshot(
        packageName = pkg,
        appHint = "当前为微信",
        clickables = clickables,
        editables = emptyList(),
        visibleTexts = texts,
        sendButtons = emptyList(),
        fingerprint = "fp",
    )

    @Test
    fun wechatChatList_withPaymentPreview_doesNotBlockClick() {
        val snapshot = snap(
            clickables = listOf(
                "吴志强 [语音] 2\"", "通讯录", "发现", "40 微信", "搜索jha", "更多功能jga",
            ),
            texts = listOf("微信(40)", "微信支付: 已支付¥7.00", "吴志强", "通讯录", "发现"),
        )
        assertTrue(RiskScreenGuard.looksLikeImSessionList(snapshot))
        assertFalse(RiskScreenGuard.isLikelyPaymentScreen(snapshot))
        assertNull(
            RiskScreenGuard.blockReason(
                snapshot,
                AgentAction(action = "click", targetText = "吴志强 [语音] 2\""),
            ),
        )
    }

    @Test
    fun realCheckout_stillBlocks() {
        val snapshot = snap(
            pkg = "com.eg.android.AlipayGphone",
            clickables = listOf("确认支付", "返回"),
            texts = listOf("收银台", "实付¥12.00", "确认支付"),
        )
        assertTrue(RiskScreenGuard.isLikelyPaymentScreen(snapshot))
        assertNotNull(
            RiskScreenGuard.blockReason(
                snapshot,
                AgentAction(action = "click", targetText = "确认支付"),
            ),
        )
    }

    @Test
    fun weakPayKeyword_onUnknownApp_blocks() {
        val snapshot = snap(
            pkg = "com.example.shop",
            clickables = listOf("支付", "返回"),
            texts = listOf("订单金额", "支付"),
        )
        assertTrue(RiskScreenGuard.isLikelyPaymentScreen(snapshot))
    }
}
