package com.fitscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fitscroll.app.ui.FitScrollNavHost
import com.fitscroll.app.ui.theme.FitScrollTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startOnWorkout = intent.getBooleanExtra(EXTRA_START_WORKOUT, false)

        setContent {
            FitScrollTheme {
                FitScrollNavHost(startOnWorkout = startOnWorkout)
            }
        }
    }

    companion object {
        /** Set by the lock screen so "earn minutes" lands straight on the camera. */
        const val EXTRA_START_WORKOUT = "start_workout"
    }
}
