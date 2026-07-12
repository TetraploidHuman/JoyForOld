package com.tetraploid.joyforold.wakeword

/**
 * 唤醒命中时暂存环形缓冲 PCM，供 ASR 连说（唤醒词与指令同句）使用。
 */
object WakeChainedAudioBridge {
    private val lock = Any()

    @Volatile
    private var preRoll: ByteArray? = null

    fun offer(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            preRoll = pcm.copyOf()
        }
    }

    fun takeAndClear(): ByteArray? = synchronized(lock) {
        val snapshot = preRoll
        preRoll = null
        snapshot
    }
}
