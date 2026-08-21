package com.fitscroll.app.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formats a bank balance for display.
 *
 * Seconds are shown only under a minute. Above that they are noise — nobody
 * scrolls differently for eleven seconds — but under a minute they are the
 * whole story, because that is when the lock is about to land.
 */
fun formatBalance(seconds: Int): String = when {
    seconds <= 0 -> "0m"
    seconds < 60 -> "${seconds}s"
    else -> formatMinutes(seconds / 60)
}

/**
 * Renders a short duration given in millis, e.g. "0.35s".
 *
 * Two decimal places, then trailing zeroes trimmed, so 350ms reads "0.35s"
 * and 600ms reads "0.6s" rather than "0.60s". Locale-fixed because this is a
 * number in a sentence about angles, not a formatted quantity, and a decimal
 * comma next to a degree sign reads as a typo.
 */
fun formatPreciseSeconds(millis: Long): String {
    val text = String.format(Locale.US, "%.2f", millis / 1000.0)
        .trimEnd('0')
        .trimEnd('.')
    return "${text}s"
}

fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "${remainder}m"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}

/**
 * Renders how long until [epochMillis], e.g. "in 21h 4m".
 */
fun formatTimeUntil(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = epochMillis - now
    if (delta <= 0) return "now"

    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta) % 60

    return when {
        hours > 0 -> "in ${hours}h ${minutes}m"
        minutes > 0 -> "in ${minutes}m"
        else -> "in under a minute"
    }
}
