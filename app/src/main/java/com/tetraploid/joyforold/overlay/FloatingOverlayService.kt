package com.tetraploid.joyforold.overlay

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tetraploid.joyforold.MainActivity
import com.tetraploid.joyforold.R
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.ui.theme.JoyForOldTheme
import com.tetraploid.joyforold.ui.theme.ThemePreferenceStore
import com.tetraploid.joyforold.util.ForegroundServicePromoter
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        AgentRuntime.initIfNeeded(applicationContext as Application)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!ForegroundServicePromoter.promote(this, NOTIFICATION_ID, createNotification())) {
            stopSelf()
            return
        }

        composeView = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setContent {
                val darkTheme = ThemePreferenceStore(this@FloatingOverlayService).isDarkTheme()
                JoyForOldTheme(darkTheme = darkTheme) {
                    FloatingOverlayContent(
                        onRun = { AgentRuntime.runAgent(applicationContext as Application) },
                        onStartVoice = { AgentRuntime.startVoiceInput() },
                        onStopVoiceAndRun = {
                            AgentRuntime.stopVoiceInputAndRunAgent(applicationContext as Application)
                        },
                        onStopVoiceOnly = { AgentRuntime.stopVoiceInput() },
                        onCancel = { AgentRuntime.clearInteraction() },
                    )
                }
            }
            visibility = View.GONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }

        windowManager.addView(composeView, layoutParams)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        AgentRuntime.refreshAccessibilityState()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private fun setDialogVisible(visible: Boolean) {
        if (!::composeView.isInitialized) return
        if (visible && AgentRuntime.isAppInForeground()) return

        composeView.visibility = if (visible) View.VISIBLE else View.GONE
        layoutParams.alpha = if (visible) 1f else 0f
        layoutParams.y = if (visible) 0 else 10_000
        layoutParams.flags = if (visible) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(composeView, layoutParams)

        if (visible) {
            composeView.isFocusable = true
            composeView.isFocusableInTouchMode = true
        } else {
            composeView.clearFocus()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "joy_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: FloatingOverlayService? = null
            private set

        private val mainHandler = Handler(Looper.getMainLooper())

        fun start(context: Context) {
            if (!OverlayPermission.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }

        fun ensureStarted(context: Context) {
            if (!OverlayPermission.canDrawOverlays(context)) return
            if (!isRunning()) start(context)
        }

        fun showDialog() {
            if (AgentRuntime.isAppInForeground()) return
            val service = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.setDialogVisible(true)
            } else {
                mainHandler.post { service.setDialogVisible(true) }
            }
        }

        fun hideDialog() {
            val service = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.setDialogVisible(false)
            } else {
                mainHandler.post { service.setDialogVisible(false) }
            }
        }

        /** 主线程 GONE；[waitFrame] 时再等一帧合成，供截图使用。 */
        suspend fun hideDialogAwait(waitFrame: Boolean = false) {
            awaitInstance()
            val service = instance ?: return
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!service::composeView.isInitialized) {
                        cont.resume(Unit)
                        return@post
                    }
                    service.setDialogVisible(false)
                    if (waitFrame) {
                        Choreographer.getInstance().postFrameCallback {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    } else if (cont.isActive) {
                        cont.resume(Unit)
                    }
                }
            }
        }

        private suspend fun awaitInstance(timeoutMs: Long = 1_500L) {
            if (instance != null) return
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (instance == null && SystemClock.uptimeMillis() < deadline) {
                delay(16)
            }
        }

        fun isRunning(): Boolean = instance != null
    }
}
