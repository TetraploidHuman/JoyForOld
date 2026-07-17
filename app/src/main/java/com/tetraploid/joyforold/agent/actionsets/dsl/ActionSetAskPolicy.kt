package com.tetraploid.joyforold.agent.actionsets.dsl

/**
 * ActionSet 窄域 askLlm 失败策略：字段未写出时先重试，耗尽后中止，禁止空目标硬点。
 */
object ActionSetAskPolicy {
    /** 同一 phase 最多尝试次数（含首次）。 */
    const val MAX_ATTEMPTS = 2

    fun unresolvedFields(
        params: ActionSetParams,
        writeFields: List<String>,
    ): List<String> =
        writeFields.filter { field -> params[field].isBlank() }

    /** [priorAttempts] 为已记录的同 phase ask 次数（尚未计入本次）。 */
    fun shouldRetry(priorAttempts: Int): Boolean =
        priorAttempts + 1 < MAX_ATTEMPTS

    fun abortFinishMessage(unresolved: List<String>): String {
        val hint = unresolved.joinToString("、").ifBlank { "目标" }
        return "没能从当前列表里确定「$hint」，请再说清楚一些。"
    }
}
