package com.tetraploid.joyforold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.ui.cortana.MainPivotScreen
import com.tetraploid.joyforold.ui.theme.JoyForOldTheme
import com.tetraploid.joyforold.ui.theme.ThemePreferenceStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.initIfNeeded(application)
        enableEdgeToEdge()
        setContent {
            val themeStore = remember { ThemePreferenceStore(this) }
            var darkTheme by remember { mutableStateOf(themeStore.isDarkTheme()) }
            JoyForOldTheme(darkTheme = darkTheme) {
                MainPivotScreen(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { enabled ->
                        darkTheme = enabled
                        themeStore.setDarkTheme(enabled)
                    },
                )
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
