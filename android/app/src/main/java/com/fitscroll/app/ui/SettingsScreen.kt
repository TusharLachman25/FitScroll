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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitscroll.app.block.AppInventory
import com.fitscroll.app.block.BlockingStatus
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.data.SettingsRepository
import com.fitscroll.app.pose.StrictnessProfile
import com.fitscroll.app.ui.theme.Crimson
import com.fitscroll.app.ui.theme.Ink
import com.fitscroll.app.ui.theme.Lime
import com.fitscroll.app.ui.theme.Surface1
import com.fitscroll.app.ui.theme.Surface2
import com.fitscroll.app.ui.theme.TextMuted

/**
 * Bank cap choices, in minutes.
 *
 * A discrete ladder rather than a continuous 15-1440 slider: the difference
 * between 313 and 320 minutes is meaningless, and the round numbers are the
 * only ones anyone actually wants.
 */
private val CAP_STEPS = listOf(15, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val bank = remember { BankRepository.get(context) }
    val state by settings.state.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextMuted)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SectionLabel("Form strictness")
        StrictnessPicker(
            level = state.strictness,
            onSelect = settings::setStrictness,
        )

        SectionLabel("Bank limit")
        BankCapPicker(
            capMinutes = state.bankCapMinutes,
            onSelect = settings::setBankCapMinutes,
        )

        SectionLabel("Locked apps")
        SettingRow(
            title = "Choose apps",
            subtitle = state.blockedPackages
                .map { AppInventory.labelFor(context, it) }
                .sorted()
                .joinToString(", ")
                .ifEmpty { "Nothing is being blocked" },
            onClick = onOpenAppPicker,
        )

        SectionLabel("Behaviour")
        ToggleRow(
            title = "Warn before locking",
            subtitle = "A heads-up with 60 seconds left, so you are not cut off mid-video.",
            checked = state.warnBeforeLock,
            onCheckedChange = settings::setWarnBeforeLock,
        )

        SectionLabel("Permissions")
        SettingRow(
            title = "Accessibility service",
            subtitle = if (BlockingStatus.isAccessibilityServiceEnabled(context)) {
                "On — FitScroll can see which app is in front"
            } else {
                "Off — nothing will be locked"
            },
            onClick = { BlockingStatus.openAccessibilitySettings(context) },
        )
        SettingRow(
            title = "Display over other apps",
            subtitle = if (BlockingStatus.canDrawOverlays(context)) {
                "On — the lock screen can open the camera for you"
            } else {
                "Off — optional. Blocking still works; the lock's button just won't jump to the camera"
            },
            onClick = { BlockingStatus.openOverlaySettings(context) },
        )

        SectionLabel("Danger zone")
        SettingRow(
            title = "Clear banked minutes",
            subtitle = "Sets your balance to zero. Blocked apps lock immediately.",
            tint = Crimson,
            onClick = { confirmClear = true },
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Everything runs on your phone. No account, no server, " +
                "no frame from the camera ever leaves the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        )

        Spacer(Modifier.height(32.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Surface1,
            title = { Text("Clear your bank?") },
            text = {
                Text(
                    "Every banked minute is deleted and your blocked apps lock " +
                        "straight away. This cannot be undone.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bank.clear()
                    confirmClear = false
                }) {
                    Text("Clear", color = Crimson)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Keep them", color = TextMuted)
                }
            },
        )
    }
}

@Composable
private fun StrictnessPicker(level: Int, onSelect: (Int) -> Unit) {
    val profile = StrictnessProfile.forLevel(level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .padding(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrictnessProfile.all().forEach { candidate ->
                val selected = candidate.level == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Lime else Surface2)
                        .clickable { onSelect(candidate.level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${candidate.level}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) Ink else TextMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(profile.label, style = MaterialTheme.typography.titleMedium, color = Lime)
        Spacer(Modifier.height(4.dp))
        Text(profile.blurb, style = MaterialTheme.typography.bodyMedium, color = TextMuted)

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Bend past ${profile.downElbowAngle.toInt()}°, " +
                "lock out past ${profile.upElbowAngle.toInt()}°, " +
                "hold your body straighter than ${profile.minBodyLineAngle.toInt()}°, " +
                "and take at least ${profile.minRepMillis / 100 / 10.0}s per rep.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun BankCapPicker(capMinutes: Int, onSelect: (Int) -> Unit) {
    // Snap to the nearest rung so a value stored before the ladder changed
    // still lands on a real position rather than an off-by-one thumb.
    val index = CAP_STEPS.indexOfFirst { it >= capMinutes }.let { if (it < 0) CAP_STEPS.lastIndex else it }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .padding(18.dp),
    ) {
        Text(
            text = formatMinutes(capMinutes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "The most you can hold at once. Reps past this stop counting, " +
                "so a huge session cannot buy a whole week.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { onSelect(CAP_STEPS[it.toInt().coerceIn(0, CAP_STEPS.lastIndex)]) },
            valueRange = 0f..CAP_STEPS.lastIndex.toFloat(),
            steps = CAP_STEPS.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = Lime,
                activeTrackColor = Lime,
                inactiveTrackColor = Surface2,
            ),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 2)
        }
        Spacer(Modifier.size(8.dp))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Lime,
                uncheckedTrackColor = Surface2,
            ),
        )
    }
}
