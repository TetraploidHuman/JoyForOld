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

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text
        result = idCardRegex.replace(result, "[身份证号]")
        result = bankCardRegex.replace(result, "[银行卡号]")
        result = phoneRegex.replace(result, "[手机号]")
        result = otpRegex.replace(result, "[验证码]")
        return result
    }
}
