package com.tetraploid.joyforold.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.tetraploid.joyforold.MainActivity
import com.tetraploid.joyforold.R
import com.tetraploid.joyforold.di.agentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class WakeWordService : Service() {
    private val logTag = "WakeWordService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null
    private var record: AudioRecord? = null
    private val serviceStartedAtMs = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        micReleased = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRecordAudioPermission()) {
            agentRuntime().appendLog("本地唤醒启动失败：缺少麦克风权限")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteToForeground()) {
            agentRuntime().appendLog("本地唤醒启动失败：无法启动前台服务（请确认麦克风权限并在应用内开启唤醒）")
            stopSelf()
            return START_NOT_STICKY
        }
        startListenLoop()
        return START_NOT_STICKY
    }

    private fun promoteToForeground(): Boolean {
        return runCatching {
            startForeground(NOTIFICATION_ID, createNotification())
            true
        }.onFailure { error ->
            Log.w(logTag, "startForeground failed", error)
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        isRunning = false
        listenJob?.cancel()
        listenJob = null
        scope.cancel()
        releaseRecorder()
        micReleased = true
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListenLoop() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            while (isActive) {
                val ran = runListenSession()
                if (!isActive) break
                if (!ran) {
                    agentRuntime().appendLog("本地唤醒监听异常退出，3 秒后重试")
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun runListenSession(): Boolean {
        val store = WakeWordConfigStore(applicationContext)
        val phrase = store.getPhrase()
        val keywordScore = store.getKeywordScore()
        val keywordThreshold = store.getKeywordThreshold()
        val confirmHits = store.getConfirmHitCount()
        val vadGateEnabled = store.isVadGateEnabled()
        val useSileroVad = store.isSileroVadEnabled()
        val secondStageEnabled = store.isSecondStageEnabled()
        val sileroGate = if (vadGateEnabled && useSileroVad) {
            runCatching { SileroVadGate(applicationContext) }.getOrNull()
        } else {
            null
        }
        val rmsGate = if (vadGateEnabled) SpeechActivityGate() else null
        val feedGate = if (vadGateEnabled) WakeWordFeedGate(sileroGate, rmsGate) else null
        val hitConfirmer = WakeWordHitConfirmer(requiredHits = confirmHits, windowMs = 1100L)
        val ringBuffer = WakeWordAudioRingBuffer()
        val detector = SherpaOnnxWakeWordDetector(
            context = applicationContext,
            keyword = phrase,
            keywordScore = keywordScore,
            keywordThreshold = keywordThreshold,
        )
        val secondStage = if (secondStageEnabled) WakeWordSecondStageVerifier(detector) else null
        if (!detector.prepare()) {
            agentRuntime().appendLog("本地唤醒模型初始化失败：${detector.modelHint()}")
            sileroGate?.release()
            return false
        }
        agentRuntime().appendLog(
            "本地唤醒已就绪：$phrase，score=$keywordScore，threshold=$keywordThreshold，" +
                "confirm=$confirmHits，vad=${vadLabel(vadGateEnabled, useSileroVad, sileroGate != null)}，" +
                "二阶段=${if (secondStageEnabled) "开" else "关"}",
        )
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(min, SAMPLE_RATE * 2)
        val audio = createAudioRecord(bufferSize)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            agentRuntime().appendLog("本地唤醒录音设备初始化失败")
            detector.release()
            sileroGate?.release()
            return false
        }
        record = audio
        micReleased = false
        runCatching { audio.startRecording() }
            .onFailure {
                agentRuntime().appendLog("本地唤醒录音启动失败：${it.message}")
                Log.w(logTag, "audio start failed", it)
                detector.release()
                sileroGate?.release()
                releaseRecorder()
                return false
            }

        val buf = ByteArray(3200)
        var frameCount = 0L
        var vadPassCount = 0L
        var hitCount = 0L
        var lastStatsAt = System.currentTimeMillis()
        var lastHitAt = 0L
        try {
            while (scope.coroutineContext.isActive && listenJob?.isActive == true) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                frameCount++
                val now = System.currentTimeMillis()
                val boostedLen = WakeWordAudioNormalizer.boostIfQuiet(buf, n)
                ringBuffer.append(buf, boostedLen)
                if (sileroGate?.hasSpeech(buf, boostedLen) == true ||
                    rmsGate?.hasSpeech(buf, boostedLen) == true
                ) {
                    vadPassCount++
                }
                if (feedGate != null && !feedGate.shouldFeed(buf, boostedLen, now)) {
                    lastStatsAt = maybeReportStats(frameCount, vadPassCount, hitCount, lastStatsAt)
                    continue
                }
                if (now - serviceStartedAtMs < STARTUP_GRACE_MS) {
                    lastStatsAt = maybeReportStats(frameCount, vadPassCount, hitCount, lastStatsAt)
                    continue
                }
                if (detector.feed(buf, boostedLen)) {
                    if (now - lastHitAt < WAKE_COOLDOWN_MS) continue
                    if (secondStage != null && !secondStage.verify(ringBuffer)) {
                        Log.d(logTag, "wake candidate rejected by second stage")
                        continue
                    }
                    if (!hitConfirmer.onCandidateHit(now)) continue
                    hitConfirmer.reset()
                    lastHitAt = now
                    hitCount++
                    WakeChainedAudioBridge.offer(ringBuffer.snapshot())
                    ringBuffer.clear()
                    Log.d(logTag, "wake hit: $phrase (#$hitCount)")
                    agentRuntime().onWakeWordDetected()
                }
                lastStatsAt = maybeReportStats(frameCount, vadPassCount, hitCount, lastStatsAt)
            }
        } finally {
            detector.release()
            sileroGate?.release()
            releaseRecorder()
        }
        return true
    }

    private fun vadLabel(enabled: Boolean, useSilero: Boolean, sileroReady: Boolean): String {
        if (!enabled) return "关"
        return if (useSilero) {
            if (sileroReady) "Silero" else "RMS(回退)"
        } else {
            "RMS"
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord {
        val preferred = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (preferred.state == AudioRecord.STATE_INITIALIZED) return preferred
        preferred.release()
        val fallback = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        return fallback
    }

    private fun maybeReportStats(
        frameCount: Long,
        vadPassCount: Long,
        hitCount: Long,
        lastStatsAt: Long,
    ): Long {
        val now = System.currentTimeMillis()
        if (now - lastStatsAt < STATS_LOG_INTERVAL_MS) return lastStatsAt
        agentRuntime().appendLog(
            "唤醒监听统计：frames=$frameCount, vadPass=$vadPassCount, hits=$hitCount",
        )
        return now
    }

    private fun releaseRecorder() {
        record?.runCatching {
            stop()
            release()
        }
        record = null
        micReleased = true
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotification(): Notification {
        val channelId = "joy_wakeword"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.wakeword_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.wakeword_notification_title))
            .setContentText(getString(R.string.wakeword_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var micReleased: Boolean = true
            private set

        private const val SAMPLE_RATE = 16000
        private const val WAKE_COOLDOWN_MS = 1500L
        private const val STARTUP_GRACE_MS = 1000L
        private const val RETRY_DELAY_MS = 3000L
        private const val STATS_LOG_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_ID = 1003

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
