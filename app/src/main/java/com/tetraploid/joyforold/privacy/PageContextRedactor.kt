package com.tetraploid.joyforold.privacy

/**
 * 发给云端 LLM 前对页面文本做本地脱敏（参考 ElderBridge RedactionEngine，扩展中国区模式）。
 */
object PageContextRedactor {
    private val phoneRegex = Regex(
        """(?<!\d)(?:\+?86[\s\-]?)?1[3-9]\d[\s\-]?\d{4}[\s\-]?\d{4}(?!\d)""",
    )
    private val idCardRegex = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    private val bankCardRegex = Regex("""(?<!\d)\d{16,19}(?!\d)""")
    private val otpRegex = Regex("""(?<!\d)\d{4,8}(?!\d)""")

    /** 发给云端 LLM 的页面上下文：含 OTP 等敏感数字脱敏。 */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        return redactOtp(redactIdentity(text))
    }

    /**
     * 本地 logcat / UI 日志：只脱敏身份证/银行卡/手机号。
     * 不对裸数字做 OTP 替换，避免 frames=1004、共 1234 字 等统计被误显示为 [验证码]。
     */
    fun redactForLog(text: String): String = redactIdentity(text)

    private fun redactIdentity(text: String): String {
        if (text.isBlank()) return text
        var result = text
        result = idCardRegex.replace(result, "[身份证号]")
        result = bankCardRegex.replace(result, "[银行卡号]")
        result = phoneRegex.replace(result, "[手机号]")
        return result
    }

    private fun redactOtp(text: String): String =
        otpRegex.replace(text, "[验证码]")
}
