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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
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
import kotlin.math.abs

class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    private var expanded by mutableStateOf(true)
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        AgentRuntime.initIfNeeded(applicationContext as Application)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, createNotification())

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setContent {
                JoyForOldTheme {
                    FloatingOverlayContent(
                        expanded = expanded,
                        onToggleExpand = { togglePanel() },
                        onClose = { stopSelf() },
                        onRun = { AgentRuntime.runAgent(applicationContext as Application) },
                        onPreview = { AgentRuntime.previewPageTree() },
                        onStartVoice = { AgentRuntime.startVoiceInput() },
                        onStopVoiceAndRun = {
                            AgentRuntime.stopVoiceInputAndRunAgent(applicationContext as Application)
                        },
                        onStopVoiceOnly = { AgentRuntime.stopVoiceInput() },
                    )
                }
            }
            setOnTouchListener(::handleDragTouch)
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }
        updateLayoutForMode()

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

    fun collapsePanel() {
        if (!expanded) return
        expanded = false
        updateLayoutForMode()
    }

    fun expandPanel() {
        if (expanded) return
        expanded = true
        updateLayoutForMode()
    }

    private fun togglePanel() {
        expanded = !expanded
        updateLayoutForMode()
    }

    private fun updateLayoutForMode() {
        layoutParams.flags = if (expanded) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        if (::composeView.isInitialized && composeView.isAttachedToWindow) {
            windowManager.updateViewLayout(composeView, layoutParams)
        }
    }

    private fun handleDragTouch(view: android.view.View, event: MotionEvent): Boolean {
        if (expanded) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    dragging = true
                }
                layoutParams.x = initialX + dx
                layoutParams.y = initialY + dy
                windowManager.updateViewLayout(composeView, layoutParams)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    togglePanel()
                }
                return true
            }
        }
        return false
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

        fun start(context: Context) {
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

        fun collapsePanel() {
            instance?.collapsePanel()
        }

        fun expandPanel() {
            instance?.expandPanel()
        }

        fun isRunning(): Boolean = instance != null
    }
}
