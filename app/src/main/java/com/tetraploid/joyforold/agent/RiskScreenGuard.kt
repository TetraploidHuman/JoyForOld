package com.tetraploid.joyforold.agent

/**
 * 支付/验证码/登录等高风险界面拦截（参考 Sahay StopBeforePayment / StopForAuth 思路）。
 *
 * 注意：微信会话列表里常出现「微信支付: 已支付¥…」消息预览，不能仅凭「支付」二字就整页封锁。
 */
object RiskScreenGuard {
    private val mutatingActions = setOf("click", "type", "send")

    /** 明确收银台/下单页文案（任意页面命中即拦） */
    private val strongPaymentCues = listOf(
        "确认支付", "立即支付", "去支付", "收银台", "实付", "应付",
        "确认付款", "立即付款", "去付款", "提交订单", "place order", "pay now", "checkout",
    )

    /** 弱线索：聊天预览也会出现，仅在非 IM 会话列表时参与判断 */
    private val weakPaymentCues = listOf("支付", "付款")

    private val authCues = listOf(
        "验证码", "短信验证", "输入密码", "登录密码", "支付密码", "指纹支付",
        "人脸识别", "刷脸", "otp", "captcha", "验证码已发送",
    )

    fun blockReason(snapshot: StructuredPageSnapshot?, action: AgentAction): String? {
        if (!mutatingActions.contains(action.action.lowercase())) return null
        val corpus = pageCorpus(snapshot)
        if (corpus.isBlank()) return null

        if (isLikelyPaymentScreen(snapshot, corpus)) {
            return "当前页面疑似支付/结账界面，已阻止自动点击或输入。请老人本人确认后再操作。"
        }
        if (authCues.any { corpus.contains(it, ignoreCase = true) }) {
            return "当前页面疑似验证码或密码输入界面，已阻止自动操作。请老人本人完成验证。"
        }
        return null
    }

    internal fun isLikelyPaymentScreen(
        snapshot: StructuredPageSnapshot?,
        corpus: String = pageCorpus(snapshot),
    ): Boolean {
        if (corpus.isBlank()) return false
        if (strongPaymentCues.any { corpus.contains(it, ignoreCase = true) }) return true
        // 微信/QQ 会话列表：预览里的「微信支付」不算收银台
        if (looksLikeImSessionList(snapshot, corpus)) return false
        return weakPaymentCues.any { corpus.contains(it, ignoreCase = true) }
    }

    /** 微信主页会话列表：底部 Tab「微信/通讯录/发现」同时出现 */
    internal fun looksLikeImSessionList(
        snapshot: StructuredPageSnapshot?,
        corpus: String = pageCorpus(snapshot),
    ): Boolean {
        val pkg = snapshot?.packageName.orEmpty()
        val wechatChrome = corpus.contains("通讯录") && corpus.contains("发现") &&
            (corpus.contains("微信") || corpus.contains("搜索"))
        if (pkg.contains("com.tencent.mm") && wechatChrome) return true
        if (pkg.contains("tencent.mm") && wechatChrome) return true
        // 无 package 时仍用 Tab 组合兜底（测试/残缺快照）
        if (wechatChrome && (corpus.contains("微信(") || corpus.contains("搜索jha") || corpus.contains("更多功能"))) {
            return true
        }
        val qqChrome = corpus.contains("联系人") && corpus.contains("动态") &&
            (pkg.contains("mobileqq") || corpus.contains("QQ"))
        return qqChrome
    }

    private fun pageCorpus(snapshot: StructuredPageSnapshot?): String {
        if (snapshot == null) return ""
        return (snapshot.clickables + snapshot.visibleTexts + snapshot.editables + snapshot.sendButtons)
            .joinToString(" ")
    }
}
