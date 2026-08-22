package com.fitscroll.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.fitscroll.app.ui.theme.Amber
import com.fitscroll.app.ui.theme.Ink
import com.fitscroll.app.ui.theme.Lime
import com.fitscroll.app.ui.theme.TextMuted

/**
 * What a withdrawn build shows instead of the app.
 *
 * Deliberately not a dead end dressed up as a crash. It says what happened, who
 * did it, and - the part that actually matters to someone who has been doing
 * push-ups for this - that their bank is still on the phone and will be there
 * when they install the supported build. Nothing here deletes anything.
 */
@Composable
fun RetiredScreen(
    message: String?,
    updateUrl: String?,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This build has been retired",
            style = MaterialTheme.typography.headlineMedium,
            color = Amber,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = message
                ?: "The developer has withdrawn this preview build of FitScroll.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Nothing is being blocked any more, and your banked minutes " +
                "are still on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        if (!updateUrl.isNullOrBlank()) {
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, updateUrl.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
            ) {
                Text("Get the current version", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = "You can turn FitScroll off under Settings → Accessibility, " +
                "or uninstall it — but uninstalling deletes your banked minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
