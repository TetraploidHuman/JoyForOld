package com.tetraploid.joyforold.accessibility

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 无障碍操作含 Thread.sleep / CountDownLatch，须在专用单线程调度器上执行，
 * 避免占用 [kotlinx.coroutines.Dispatchers.Default] 线程池。
 */
object AccessibilityActionDispatcher {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "joy-a11y-actions").apply { isDaemon = true }
    }

    val dispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> runAction(block: () -> T): T = withContext(dispatcher) { block() }
}
