package com.fitscroll.app.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    var armed by remember { mutableStateOf(BlockingStatus.isFullyArmed(context)) }

    // The balance moves without any action on this screen: credits expire on a
    // wall clock, and the accessibility service drains it from another
    // component entirely. A one-second tick keeps the number honest, and picks
    // up permission changes made in system Settings on the way back.
    LaunchedEffect(Unit) {
        while (true) {
            bank.refresh()
            armed = BlockingStatus.isFullyArmed(context)
            delay(1_000)
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

        if (!armed) {
            SetupWarningCard(
                accessibilityOn = BlockingStatus.isAccessibilityServiceEnabled(context),
                overlayOn = BlockingStatus.canDrawOverlays(context),
                onFixAccessibility = { BlockingStatus.openAccessibilitySettings(context) },
                onFixOverlay = { BlockingStatus.openOverlaySettings(context) },
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
                nextExpiryAt == null -> "Ready to spend."
                expiringSoonSeconds > 0 ->
                    "${formatBalance(expiringSoonSeconds)} expires within the hour — use it or lose it."
                else -> "Oldest minutes expire ${formatTimeUntil(nextExpiryAt)}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (expiringSoonSeconds > 0 && !empty) Amber else TextMuted,
        )
    }
}

@Composable
private fun SetupWarningCard(
    accessibilityOn: Boolean,
    overlayOn: Boolean,
    onFixAccessibility: () -> Unit,
    onFixOverlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Amber.copy(alpha = 0.12f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Amber)
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Blocking is not active",
                style = MaterialTheme.typography.titleMedium,
                color = Amber,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You can still bank minutes, but nothing will be locked until " +
                "both permissions are on. Android requires each to be granted by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        if (!accessibilityOn) {
            PermissionRow("Accessibility service", onFixAccessibility)
        }
        if (!overlayOn) {
            PermissionRow("Display over other apps", onFixOverlay)
        }
    }
}

@Composable
private fun PermissionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        Text("Turn on", style = MaterialTheme.typography.labelLarge, color = Amber)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Amber)
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
