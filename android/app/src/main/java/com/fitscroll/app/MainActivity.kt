package com.fitscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fitscroll.app.ui.theme.FitScrollTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FitScrollTheme { Placeholder() } }
    }

    companion object {
        /** Set by the lock screen so "earn minutes" lands straight on the camera. */
        const val EXTRA_START_WORKOUT = "start_workout"
    }
}

@Composable
private fun Placeholder() {
    Text("FitScroll")
}
