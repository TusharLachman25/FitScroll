package com.fitscroll.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fitscroll.app.ui.FitScrollNavHost
import com.fitscroll.app.ui.theme.FitScrollTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Bumped each time something outside the activity asks for the camera.
     *
     * The extra used to be read only in onCreate, which is fine exactly as long
     * as every arrival builds a fresh activity. This one is singleTask, and an
     * intent that reaches an instance already in the task is delivered to
     * onNewIntent instead - a start destination computed once cannot answer
     * that, and the user would land on the home screen at the moment they had
     * just been told to do push-ups.
     *
     * A counter rather than a flag, so two requests in a row are two
     * navigations rather than one and a no-op.
     */
    private val workoutRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startOnWorkout = intent.getBooleanExtra(EXTRA_START_WORKOUT, false)

        setContent {
            FitScrollTheme {
                FitScrollNavHost(
                    startOnWorkout = startOnWorkout,
                    workoutRequests = workoutRequests.asStateFlow(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept, so that a later configuration change re-reads the intent that
        // is actually current rather than the one the task was created with.
        setIntent(intent)

        if (intent.getBooleanExtra(EXTRA_START_WORKOUT, false)) {
            workoutRequests.value++
        }
    }

    companion object {
        /** Set by the lock screen so "earn minutes" lands straight on the camera. */
        const val EXTRA_START_WORKOUT = "start_workout"
    }
}
