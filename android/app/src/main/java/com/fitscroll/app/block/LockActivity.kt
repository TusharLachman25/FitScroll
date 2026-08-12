package com.fitscroll.app.block

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitscroll.app.MainActivity
import com.fitscroll.app.ui.theme.Crimson
import com.fitscroll.app.ui.theme.FitScrollTheme
import com.fitscroll.app.ui.theme.Ink
import com.fitscroll.app.ui.theme.TextMuted

/**
 * The screen that lands on top of a blocked app when the bank runs dry.
 *
 * Raised by [FitScrollAccessibilityService] both when a blocked app is opened
 * with an empty balance and the instant the balance empties mid-session.
 */
class LockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        val blockedLabel = blockedPackage
            ?.let { AppInventory.labelFor(this, it) }
            ?: "That app"

        // Back must not return to the app we just ejected them from. Sending
        // them home instead makes the lock feel like a wall rather than a
        // dialog to dismiss.
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setContent {
            FitScrollTheme {
                LockScreen(
                    blockedLabel = blockedLabel,
                    onEarn = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                .putExtra(MainActivity.EXTRA_START_WORKOUT, true),
                        )
                        finish()
                    },
                    onDismiss = { goHome() },
                )
            }
        }
    }

    /**
     * Leaving by any route tears the lock down, so it is never sitting stale in
     * the recents list waiting to ambush the next launch of FitScroll.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
    }
}

/** Adds an OnBackPressedCallback without the ceremony, since we only need one. */
private fun androidx.activity.OnBackPressedDispatcher.addCallback(
    owner: androidx.lifecycle.LifecycleOwner,
    handler: () -> Unit,
) {
    addCallback(owner, object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = handler()
    })
}

@Composable
private fun LockScreen(
    blockedLabel: String,
    onEarn: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Ink, Crimson.copy(alpha = 0.16f), Ink)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Crimson.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Crimson,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "$blockedLabel is locked",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Your bank is empty. One push-up buys one minute — " +
                    "and every minute you bank is good for 24 hours.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onEarn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Ink,
                ),
            ) {
                Text("Drop and earn minutes", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", color = TextMuted)
            }
        }
    }
}
