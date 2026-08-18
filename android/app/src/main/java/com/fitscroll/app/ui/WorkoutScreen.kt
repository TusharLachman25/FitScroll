package com.fitscroll.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitscroll.app.pose.PoseAnalyzer
import com.fitscroll.app.ui.theme.Amber
import com.fitscroll.app.ui.theme.Crimson
import com.fitscroll.app.ui.theme.Ink
import com.fitscroll.app.ui.theme.Lime
import com.fitscroll.app.ui.theme.TextMuted
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The camera screen where minutes are earned.
 *
 * Shows the tracked skeleton live so a rejected rep is legible as feedback
 * rather than as a bug.
 */
@Composable
fun WorkoutScreen(
    onBankedSet: (grantedSeconds: Int, wastedSeconds: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: WorkoutViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var refused by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        refused = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Re-read on every resume, because the grant can happen somewhere this
    // screen never sees: the user leaving for system Settings and coming back.
    // Without this the camera stays dark behind a prompt for a permission that
    // has already been given.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasCameraPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    // Android stops showing the system dialog once the permission has been
    // refused firmly enough, and `launch` then returns denied without putting
    // anything on screen. A prompt whose only button silently does nothing is
    // a dead end in the one screen the whole app exists to reach, so past that
    // point the button has to hand over to system Settings instead.
    val mustUseSettings = refused && !hasCameraPermission && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    // Doing push-ups means not touching the phone, so the display times out
    // mid-set and the camera stops seeing anything. Held on the composition's
    // own view rather than as a window flag, so it is scoped to this screen and
    // released automatically on the way out — the home screen has no business
    // keeping the display awake.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // One buzz per counted rep, so you can keep your head down and still know
    // it registered.
    LaunchedEffect(state.reps) {
        if (state.reps > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (hasCameraPermission) {
            CameraFeed(
                useFrontCamera = state.useFrontCamera,
                onPoseResult = viewModel::onPoseResult,
                modifier = Modifier.fillMaxSize(),
            )
            SkeletonOverlay(
                frame = state.skeleton,
                formOk = state.formOk,
                depth = state.depth,
                mirrored = state.useFrontCamera,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraPermissionPrompt(
                mustUseSettings = mustUseSettings,
                onGrant = {
                    if (mustUseSettings) openAppSettings(context)
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Scrims keep the white rep count legible over a bright room without
        // hiding the body in the middle of the frame.
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.92f)))),
        )

        WorkoutTopBar(
            // Saying so is the difference between "this level is oddly lenient"
            // and knowing the legs are out of shot, which is fixable by moving
            // the phone.
            strictnessLabel = if (state.formJudged || !state.tracking) {
                state.strictness.label
            } else {
                "${state.strictness.label} · back not checked"
            },
            onBack = onBack,
            onFlip = viewModel::flipCamera,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        WorkoutHud(
            state = state,
            onBank = {
                val outcome = viewModel.bankSet()
                onBankedSet(outcome.grantedSeconds, outcome.wastedSeconds)
            },
            onDiscard = viewModel::discardSet,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun WorkoutTopBar(
    strictnessLabel: String,
    onBack: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = strictnessLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onFlip) {
            Icon(Icons.Rounded.Cameraswitch, contentDescription = "Flip camera", tint = Color.White)
        }
    }
}

@Composable
private fun WorkoutHud(
    state: WorkoutUiState,
    onBank: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val depth by animateFloatAsState(state.depth, label = "depth")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = state.rejection != null) {
            Text(
                text = state.rejection.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Crimson,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { depth },
                modifier = Modifier.size(132.dp),
                color = if (state.formOk) Lime else Crimson,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 8.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${state.reps}",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                )
                Text(
                    text = if (state.reps == 1) "rep" else "reps",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = state.coaching.message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.formOk) Color.White.copy(alpha = 0.85f) else Amber,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onBank,
            enabled = state.reps > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lime,
                contentColor = Ink,
                disabledContainerColor = Color.White.copy(alpha = 0.10f),
                disabledContentColor = TextMuted,
            ),
        ) {
            Text(
                text = if (state.reps > 0) "Bank ${state.reps} min" else "Do a rep to bank minutes",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        AnimatedVisibility(visible = state.reps > 0) {
            TextButton(onClick = onDiscard) {
                Text("Discard set", color = TextMuted)
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    mustUseSettings: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access needed",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (mustUseSettings) {
                "Android will not ask again now that it has been refused, so the " +
                    "switch has to be flipped in system Settings — Permissions, " +
                    "then Camera."
            } else {
                "FitScroll counts your push-ups on the phone itself. " +
                    "No video is recorded, and no frame ever leaves the device."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, shape = RoundedCornerShape(16.dp)) {
            Text(if (mustUseSettings) "Open app settings" else "Allow camera")
        }
    }
}

/**
 * Opens FitScroll's own page in system Settings, the only route back to a
 * permission Android has stopped prompting for.
 */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}

/**
 * Binds CameraX preview plus pose analysis to the composition lifecycle.
 */
@Composable
private fun CameraFeed(
    useFrontCamera: Boolean,
    onPoseResult: (com.fitscroll.app.pose.PoseResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    // A dedicated single thread keeps inference off the main thread while
    // guaranteeing frames are analysed in order.
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { PoseAnalyzer(onPoseResult) }

    // Held so teardown can unbind the provider it actually bound, rather than
    // blocking the main thread on getInstance().get() at the worst possible
    // moment — leaving the screen. The future is normally already resolved, but
    // "normally" is not a guarantee worth an ANR on a cold camera stack.
    val boundProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(useFrontCamera) {
        val provider = context.awaitCameraProvider()
        boundProvider.value = provider

        val preview = Preview.Builder().build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            // Dropping stale frames matters more than analysing every one: a
            // backlog would report a descent the body has already finished.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }

        val selector =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Order matters: stop the frames first, then close what consumes
            // them. Releasing the detector while CameraX still had a frame in
            // flight put a closed detector on the analysis thread, which throws.
            runCatching { boundProvider.value?.unbindAll() }
            analyzer.release()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener(
                { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(this),
            )
        }
    }
