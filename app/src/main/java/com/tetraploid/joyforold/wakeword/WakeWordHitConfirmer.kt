package com.tetraploid.joyforold.wakeword

/**
 * 二次确认：在短时间窗内累计多次 KWS 命中后再真正触发唤醒。
 * 允许模型阈值偏灵敏（提高召回），靠连续命中过滤偶发误报。
 */
class WakeWordHitConfirmer(
    private val requiredHits: Int,
    private val windowMs: Long = 900L,
) {
    private val hitTimes = ArrayDeque<Long>()

    fun onCandidateHit(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (requiredHits <= 1) return true
        hitTimes.addLast(nowMs)
        while (hitTimes.isNotEmpty() && nowMs - hitTimes.first() > windowMs) {
            hitTimes.removeFirst()
        }
        return hitTimes.size >= requiredHits
    }

    fun reset() {
        hitTimes.clear()
    }
}
