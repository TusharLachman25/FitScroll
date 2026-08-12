package com.fitscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fitscroll.app.ui.theme.Ink
import kotlinx.coroutines.launch

private object Route {
    const val HOME = "home"
    const val WORKOUT = "workout"
    const val SETTINGS = "settings"
    const val APP_PICKER = "apps"
}

@Composable
fun FitScrollNavHost(startOnWorkout: Boolean) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Ink)) {
        NavHost(
            navController = navController,
            // Arriving from the lock screen drops the user straight on the
            // camera. Making them tap through the home screen first, at the
            // exact moment they have been told to do push-ups, is friction in
            // the wrong place.
            startDestination = if (startOnWorkout) Route.WORKOUT else Route.HOME,
        ) {
            composable(Route.HOME) {
                HomeScreen(
                    onStartWorkout = { navController.navigate(Route.WORKOUT) },
                    onOpenSettings = { navController.navigate(Route.SETTINGS) },
                )
            }

            composable(Route.WORKOUT) {
                WorkoutScreen(
                    onBankedSet = { granted, wasted ->
                        scope.launch {
                            snackbarHostState.showMessage(granted, wasted)
                        }
                        if (!navController.popBackStack()) {
                            navController.navigate(Route.HOME) {
                                popUpTo(Route.WORKOUT) { inclusive = true }
                            }
                        }
                    },
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Route.HOME) {
                                popUpTo(Route.WORKOUT) { inclusive = true }
                            }
                        }
                    },
                )
            }

            composable(Route.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAppPicker = { navController.navigate(Route.APP_PICKER) },
                )
            }

            composable(Route.APP_PICKER) {
                AppPickerScreen(onBack = { navController.popBackStack() })
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

/**
 * Reports what a set actually bought.
 *
 * The overflow is called out explicitly rather than quietly swallowed: doing
 * thirty push-ups and gaining four minutes is confusing unless the bank limit
 * is named as the reason.
 */
private suspend fun SnackbarHostState.showMessage(grantedMinutes: Int, wastedMinutes: Int) {
    val message = when {
        grantedMinutes <= 0 && wastedMinutes > 0 ->
            "Bank is already full — those reps earned nothing"
        wastedMinutes > 0 ->
            "Banked ${formatMinutes(grantedMinutes)} — ${formatMinutes(wastedMinutes)} hit your bank limit"
        grantedMinutes > 0 ->
            "Banked ${formatMinutes(grantedMinutes)}"
        else -> return
    }
    showSnackbar(message)
}
