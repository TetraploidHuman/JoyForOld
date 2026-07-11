package com.tetraploid.joyforold.speech

import android.os.Handler
import android.os.Looper
import com.tetraploid.joyforold.speech.api.SpeechInput
import com.tetraploid.joyforold.speech.api.SpeechInputSession
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class DoubaoSpeechInput(
    private val client: DoubaoAsrClient,
) : SpeechInput {
    @Volatile
    private var active = false
    @Volatile
    private var sessionDelivered = false
    private val retryHandler = Handler(Looper.getMainLooper())

    override suspend fun prepareConnection(session: SpeechInputSession) {
        if (active) return
        suspendCancellableCoroutine { cont ->
            client.prepareConnection(
                shortUtterance = session.shortUtterance,
                onReady = {
                    if (cont.isActive) cont.resume(Unit)
                },
                onError = {
                    // 预连失败不阻断：start() 会走完整建连流程
                    if (cont.isActive) cont.resume(Unit)
                },
            )
            cont.invokeOnCancellation {
                if (!active) client.cancelPrepare()
            }
        }
    }

    override fun start(session: SpeechInputSession) {
        if (active) return
        sessionDelivered = false
        startWithRetry(session, attempt = 0)
    }

    private fun startWithRetry(session: SpeechInputSession, attempt: Int) {
        if (active) return
        active = true
        client.start(
            onPartialText = session.onPartialText,
            onFinalText = { text ->
                sessionDelivered = true
                active = false
                session.onFinalText(text)
            },
            onError = { error ->
                active = false
                if (sessionDelivered) return@start
                client.cancelSession()
                if (attempt < MAX_CONNECT_RETRIES && isRetryableAsrError(error)) {
                    retryHandler.postDelayed(
                        { startWithRetry(session, attempt + 1) },
                        RETRY_DELAY_MS * (attempt + 1),
                    )
                } else {
                    val suffix = if (attempt > 0) "（已自动重试 $attempt 次）" else ""
                    session.onError("$error$suffix")
                }
            },
            shortUtterance = session.shortUtterance,
        )
    }

    override fun cancelPreparedConnection() {
        client.cancelPrepare()
    }

    override suspend fun stop(onFinalText: (String) -> Unit) {
        retryHandler.removeCallbacksAndMessages(null)
        client.stop { text ->
            sessionDelivered = true
            onFinalText(text)
        }
        active = false
    }

    override fun isActive(): Boolean = active

    companion object {
        private const val MAX_CONNECT_RETRIES = 2
        private const val RETRY_DELAY_MS = 600L

        internal fun isRetryableAsrError(message: String): Boolean {
            return message.contains("无法连接") ||
                message.contains("连接失败") ||
                message.contains("超时") ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("Failed to connect", ignoreCase = true)
        }
    }
}
