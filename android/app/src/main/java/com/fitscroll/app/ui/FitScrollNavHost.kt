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
 *
 * Takes seconds rather than minutes because the last credit before the cap is
 * routinely a part-minute. Rounding down to whole minutes first meant a set
 * that banked forty seconds reported "0m" against "0m" wasted, fell through
 * every branch, and showed no snackbar at all — reps that did land, vanishing
 * without acknowledgement.
 */
private suspend fun SnackbarHostState.showMessage(grantedSeconds: Int, wastedSeconds: Int) {
    val message = when {
        grantedSeconds <= 0 && wastedSeconds > 0 ->
            "Bank is already full — those reps earned nothing"
        wastedSeconds > 0 ->
            "Banked ${formatBalance(grantedSeconds)} — ${formatBalance(wastedSeconds)} hit your bank limit"
        grantedSeconds > 0 ->
            "Banked ${formatBalance(grantedSeconds)}"
        else -> return
    }
    showSnackbar(message)
}
