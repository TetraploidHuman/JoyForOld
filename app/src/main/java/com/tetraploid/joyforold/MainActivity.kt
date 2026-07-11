package com.tetraploid.joyforold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.ui.cortana.MainPivotScreen
import com.tetraploid.joyforold.ui.theme.JoyForOldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.initIfNeeded(application)
        enableEdgeToEdge()
        setContent {
            JoyForOldTheme {
                MainPivotScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentRuntime.refreshAccessibilityState()
    }

    override fun onStart() {
        super.onStart()
        AgentRuntime.setAppInForeground(true)
    }

    override fun onStop() {
        AgentRuntime.setAppInForeground(false)
        super.onStop()
    }
}
