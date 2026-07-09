package com.tetraploid.joyforold.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class AgentRunContext {
    private val paused = AtomicBoolean(false)
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var currentStep: Int = 0
        internal set

    @Volatile
    var statusMessage: String = ""
        internal set

    fun cancel() {
        cancelled = true
        paused.set(false)
    }

    fun pause() {
        paused.set(true)
        statusMessage = "已暂停"
    }

    fun resume() {
        paused.set(false)
        statusMessage = "继续执行"
    }

    fun isPaused(): Boolean = paused.get()

    suspend fun awaitContinuation() {
        if (cancelled) throw CancellationException("用户已停止 Agent")
        while (paused.get() && !cancelled) {
            delay(150)
        }
        if (cancelled) throw CancellationException("用户已停止 Agent")
    }

    internal fun updateProgress(step: Int, message: String) {
        currentStep = step
        statusMessage = message
    }
}
