package com.fitscroll.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.fitscroll.app.block.AppInventory
import com.fitscroll.app.block.BlockingStatus
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.data.SettingsRepository
import com.fitscroll.app.ui.theme.Amber
import com.fitscroll.app.ui.theme.Crimson
import com.fitscroll.app.ui.theme.Ink
import com.fitscroll.app.ui.theme.Lime
import com.fitscroll.app.ui.theme.Surface1
import com.fitscroll.app.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val bank = remember { BankRepository.get(context) }
    val settings = remember { SettingsRepository.get(context) }

    val bankState by bank.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()

    var accessibilityOn by remember {
        mutableStateOf(BlockingStatus.isAccessibilityServiceEnabled(context))
    }
    var overlayOn by remember { mutableStateOf(BlockingStatus.canDrawOverlays(context)) }

    // Asked once, on the first visit, and never again. Notifications carry the
    // live balance and the expiry warning, both of which are worth having - but
    // neither is load-bearing, and an app about self-control that nags for
    // permissions every time it opens has picked the wrong fight. The result is
    // deliberately ignored: refusing simply means those two messages never
    // arrive.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        if (needed && !settings.current.notificationsRequested) {
            settings.markNotificationsRequested()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The balance moves without any action on this screen: credits expire on a
    // wall clock, and the accessibility service drains it from another
    // component entirely. A one-second tick keeps the number honest, and picks
    // up permission changes made in system Settings on the way back.
    //
    // Bound to RESUMED rather than left to run for the lifetime of the
    // composition. A composition outlives the screen being visible, so an
    // unscoped loop kept polling once a second for as long as the process
    // lived — a Settings.Secure lookup, an AppOps check and a ledger refresh
    // every second behind a phone that was in a pocket. Nothing here is worth
    // observing while nobody is looking at it.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                bank.refresh()
                accessibilityOn = BlockingStatus.isAccessibilityServiceEnabled(context)
                overlayOn = BlockingStatus.canDrawOverlays(context)
                delay(1_000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FitScroll",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = TextMuted)
            }
        }

        Spacer(Modifier.height(8.dp))

        BalanceCard(
            balanceSeconds = bankState.balanceSeconds,
            expiringSoonSeconds = bankState.expiringWithinHourSeconds,
            nextExpiryAt = bankState.nextExpiryAt,
        )

        Spacer(Modifier.height(16.dp))

        // Only the accessibility service is load-bearing. The lock screen is an
        // accessibility overlay the service is granted directly, so blocking
        // works without the draw-over permission — that one only buys the
        // shortcut from the lock straight into the camera.
        if (!accessibilityOn) {
            BlockingOffCard(
                onFix = { BlockingStatus.openAccessibilitySettings(context) },
            )
            Spacer(Modifier.height(16.dp))
        } else if (!overlayOn) {
            OverlayHintCard(
                onFix = { BlockingStatus.openOverlaySettings(context) },
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Today",
                value = "${bankState.repsToday}",
                caption = "push-ups",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "All time",
                value = "${bankState.repsAllTime}",
                caption = "push-ups",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        BlockedAppsSummary(
            packages = settingsState.blockedPackages,
            onClick = onOpenSettings,
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onStartWorkout,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
        ) {
            Icon(Icons.Rounded.FitnessCenter, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text("Earn minutes", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BalanceCard(
    balanceSeconds: Int,
    expiringSoonSeconds: Int,
    nextExpiryAt: Long?,
) {
    val empty = balanceSeconds <= 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    if (empty) listOf(Crimson.copy(alpha = 0.18f), Surface1)
                    else listOf(Lime.copy(alpha = 0.16f), Surface1),
                ),
            )
            .padding(24.dp),
    ) {
        Text(
            text = if (empty) "BANK EMPTY" else "BANKED",
            style = MaterialTheme.typography.labelSmall,
            color = if (empty) Crimson else Lime,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatBalance(balanceSeconds),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                empty -> "Your blocked apps are locked. One push-up buys one minute."
                // No null case for nextExpiryAt below it: a live balance always
                // has a live credit behind it, so the empty branch above is the
                // only way it can be absent.
                expiringSoonSeconds > 0 ->
                    "${formatBalance(expiringSoonSeconds)} expires within the hour — use it or lose it."
                nextExpiryAt != null -> "Oldest minutes expire ${formatTimeUntil(nextExpiryAt)}."
                else -> "Ready to spend."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (expiringSoonSeconds > 0 && !empty) Amber else TextMuted,
        )
    }
}

@Composable
private fun BlockingOffCard(onFix: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Crimson.copy(alpha = 0.13f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Crimson)
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Blocking is not active",
                style = MaterialTheme.typography.titleMedium,
                color = Crimson,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You can bank minutes, but nothing will be locked until the " +
                "accessibility service is on. Android only lets you grant it by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow("Turn on accessibility", onFix, tint = Crimson)
    }
}

@Composable
private fun OverlayHintCard(onFix: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Amber.copy(alpha = 0.10f))
            .padding(18.dp),
    ) {
        Text(
            text = "Optional",
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Blocking works already. Allowing FitScroll to display over " +
                "other apps just lets the lock screen's button open the camera " +
                "for you instead of you finding the app yourself.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        PermissionRow("Allow display over other apps", onFix, tint = Amber)
    }
}

@Composable
private fun PermissionRow(
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tint)
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .padding(18.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
}

@Composable
private fun BlockedAppsSummary(packages: Set<String>, onClick: () -> Unit) {
    val context = LocalContext.current
    val names = remember(packages) {
        packages.map { AppInventory.labelFor(context, it) }.sorted()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Crimson.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${packages.size}", color = Crimson, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (packages.size == 1) "Locked app" else "Locked apps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (names.isEmpty()) "Nothing is being blocked" else names.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}
