package com.tetraploid.joyforold.speech

import android.content.Context
import com.tetraploid.joyforold.speech.api.TtsOutput
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

sealed class BargeInSpeakOutcome {
    data object Completed : BargeInSpeakOutcome()

    data class BargedIn(val preRollPcm: ByteArray) : BargeInSpeakOutcome()
}

object VoiceBargeInHelper {
    suspend fun speakWithBargeIn(
        context: Context,
        tts: TtsOutput,
        text: String,
    ): BargeInSpeakOutcome {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return BargeInSpeakOutcome.Completed

        val monitor = VoiceBargeInMonitor(context)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val monitorJob = async(Dispatchers.IO) {
                    monitor.runUntilStopped()
                }
                val bargeJob = async(Dispatchers.IO) {
                    monitor.awaitBargeIn()
                }
                val speakJob = async(Dispatchers.Main.immediate) {
                    tts.speakAndAwait(trimmed, flush = true)
                }
                select {
                    bargeJob.onAwait {
                        withContext(Dispatchers.Main.immediate) {
                            tts.stop()
                        }
                        speakJob.cancel()
                        monitorJob.cancel()
                        delay(VoiceBargeInMonitor.ECHO_DECAY_MS)
                        BargeInSpeakOutcome.BargedIn(monitor.takePreRoll())
                    }
                    speakJob.onAwait {
                        monitorJob.cancel()
                        bargeJob.cancel()
                        BargeInSpeakOutcome.Completed
                    }
                }
            }
        }.also {
            monitor.release()
        }
    }
}
