package com.fitscroll.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The palette is deliberately dark in both system themes. FitScroll is used on
// a gym floor and, more importantly, on a lock screen that appears over
// Instagram at night; a white flash there is genuinely unpleasant.
val Ink = Color(0xFF0B0F14)
val Surface1 = Color(0xFF141A22)
val Surface2 = Color(0xFF1D242E)
val Outline = Color(0xFF2C3644)

val Lime = Color(0xFF4ADE80)
val LimeDeep = Color(0xFF22C55E)
val Amber = Color(0xFFFBBF24)
val Crimson = Color(0xFFF43F5E)

val TextPrimary = Color(0xFFEDF2F7)
val TextMuted = Color(0xFF94A3B8)

private val FitScrollColors = darkColorScheme(
    primary = Lime,
    onPrimary = Ink,
    primaryContainer = LimeDeep,
    onPrimaryContainer = Ink,
    secondary = Amber,
    onSecondary = Ink,
    error = Crimson,
    onError = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    outline = Outline,
)

private val FitScrollTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun FitScrollTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FitScrollColors,
        typography = FitScrollTypography,
        content = content,
    )
}
