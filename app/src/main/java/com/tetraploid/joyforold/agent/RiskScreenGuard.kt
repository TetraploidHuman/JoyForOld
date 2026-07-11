package com.tetraploid.joyforold.agent

/**
 * 支付/验证码/登录等高风险界面拦截（参考 Sahay StopBeforePayment / StopForAuth 思路）。
 */
object RiskScreenGuard {
    private val mutatingActions = setOf("click", "type", "send")

    private val paymentCues = listOf(
        "支付", "付款", "确认支付", "立即支付", "去支付", "收银台", "实付", "应付",
        "place order", "pay now", "checkout",
    )

    private val authCues = listOf(
        "验证码", "短信验证", "输入密码", "登录密码", "支付密码", "指纹支付",
        "人脸识别", "刷脸", "otp", "captcha", "验证码已发送",
    )

    fun blockReason(snapshot: StructuredPageSnapshot?, action: AgentAction): String? {
        if (!mutatingActions.contains(action.action.lowercase())) return null
        val corpus = pageCorpus(snapshot)
        if (corpus.isBlank()) return null

        if (paymentCues.any { corpus.contains(it, ignoreCase = true) }) {
            return "当前页面疑似支付/结账界面，已阻止自动点击或输入。请老人本人确认后再操作。"
        }
        if (authCues.any { corpus.contains(it, ignoreCase = true) }) {
            return "当前页面疑似验证码或密码输入界面，已阻止自动操作。请老人本人完成验证。"
        }
        return null
    }

    private fun pageCorpus(snapshot: StructuredPageSnapshot?): String {
        if (snapshot == null) return ""
        return (snapshot.clickables + snapshot.visibleTexts + snapshot.editables + snapshot.sendButtons)
            .joinToString(" ")
    }
}
