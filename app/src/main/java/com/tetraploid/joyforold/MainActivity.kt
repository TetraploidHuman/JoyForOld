package com.tetraploid.joyforold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tetraploid.joyforold.ui.DemoScreen
import com.tetraploid.joyforold.ui.theme.JoyForOldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JoyForOldTheme {
                DemoScreen()
            }
        }
    }
}
