package com.putnamworkbook.offline

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as PutnamApplication).container
        setContent {
            PutnamApp(container, onKeepScreenAwakeChanged = { keepAwake ->
                updateKeepScreenAwake(window, workspaceActive = keepAwake, enabled = true)
            })
        }
    }
}

fun updateKeepScreenAwake(window: Window, workspaceActive: Boolean, enabled: Boolean) {
    if (workspaceActive && enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
