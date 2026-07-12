package com.tetraploid.joyforold.agent

enum class AgentVerificationStatus {
    VERIFIED,
    NOT_APPLICABLE,
    FAILED,
}

data class AgentVerificationResult(
    val status: AgentVerificationStatus,
    val message: String,
    val expected: String? = null,
    val actual: String? = null,
) {
    val failed: Boolean get() = status == AgentVerificationStatus.FAILED

    fun toFeedbackLine(): String? {
        if (status == AgentVerificationStatus.NOT_APPLICABLE) return null
        val prefix = when (status) {
            AgentVerificationStatus.VERIFIED -> "【执行验证】通过"
            AgentVerificationStatus.FAILED -> "【执行验证】未通过"
            AgentVerificationStatus.NOT_APPLICABLE -> return null
        }
        return buildString {
            append(prefix).append("：").append(message)
            if (!expected.isNullOrBlank()) append("（期望：$expected")
            if (!actual.isNullOrBlank()) {
                append(if (expected.isNullOrBlank()) "（" else "，实际：")
                append(actual)
            }
            if (!expected.isNullOrBlank() || !actual.isNullOrBlank()) append("）")
        }
    }

    companion object {
        fun verified(message: String, expected: String? = null, actual: String? = null) =
            AgentVerificationResult(AgentVerificationStatus.VERIFIED, message, expected, actual)

        fun notApplicable(message: String) =
            AgentVerificationResult(AgentVerificationStatus.NOT_APPLICABLE, message)

        fun failed(message: String, expected: String? = null, actual: String? = null) =
            AgentVerificationResult(AgentVerificationStatus.FAILED, message, expected, actual)
    }
}
