package com.fitscroll.app.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formats a bank balance for display. The one place that decides how a balance
 * reads, so the notification, the lock screen and the home screen can never
 * disagree about the same number — which they did, one saying "90m" while the
 * other said "1h 30m".
 *
 * Seconds are shown right up to the hour mark, and that is the point: a number
 * that only moves once a minute is indistinguishable from a number that is
 * stuck, so the drain looked broken whether or not it was. Past an hour they
 * would be noise, and the readout settles to hours and minutes.
 *
 * The seconds are padded so a ticking balance keeps its width instead of
 * twitching a pixel every ten seconds.
 */
fun formatBalance(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val secs = total % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs.toString().padStart(2, '0')}s"
        else -> "${secs}s"
    }
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

/**
 * Renders a whole number of minutes as a label, e.g. "2h 30m".
 *
 * For settings that are *chosen* in minutes — the bank cap — rather than for a
 * balance being watched: a ceiling of "24h" wants no seconds attached to it.
 */
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
