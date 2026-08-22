package com.fitscroll.app.ui

import com.fitscroll.app.pose.StrictnessProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers the user reads back.
 *
 * Worth pinning down because these are the app explaining its own rules: a
 * balance that rounds the wrong way, or a strictness figure that disagrees with
 * the counter enforcing it, reads as the app being broken rather than as a
 * formatting slip.
 */
class FormatTest {

    // -------------------------------------------------------------- balance

    @Test
    fun `an empty bank reads as zero minutes, not zero seconds`() {
        assertEquals("0m", formatBalance(0))
        assertEquals("0m", formatBalance(-30))
    }

    @Test
    fun `under a minute is shown in seconds, because that is when the lock lands`() {
        assertEquals("45s", formatBalance(45))
        assertEquals("59s", formatBalance(59))
    }

    @Test
    fun `a minute and over drops the seconds`() {
        assertEquals("1m", formatBalance(60))
        assertEquals("1m", formatBalance(119))
        assertEquals("2m", formatBalance(120))
    }

    @Test
    fun `hours are split out once there are any`() {
        assertEquals("1h", formatBalance(3_600))
        assertEquals("1h 1m", formatBalance(3_660))
        assertEquals("24h", formatBalance(86_400))
    }

    @Test
    fun `whole hours do not carry a trailing zero minutes`() {
        assertEquals("2h", formatMinutes(120))
        assertEquals("2h 30m", formatMinutes(150))
        assertEquals("30m", formatMinutes(30))
    }

    // ------------------------------------------------------- precise seconds

    @Test
    fun `a part second keeps both digits`() {
        assertEquals("0.35s", formatPreciseSeconds(350))
        assertEquals("0.45s", formatPreciseSeconds(450))
        assertEquals("0.75s", formatPreciseSeconds(750))
    }

    @Test
    fun `a trailing zero is trimmed rather than printed`() {
        assertEquals("0.6s", formatPreciseSeconds(600))
        assertEquals("0.9s", formatPreciseSeconds(900))
    }

    @Test
    fun `a whole second loses the decimal point entirely`() {
        assertEquals("1s", formatPreciseSeconds(1_000))
        assertEquals("2s", formatPreciseSeconds(2_000))
    }

    @Test
    fun `every strictness level reports the duration the counter enforces`() {
        // This used to divide by 100 and then by 10 in Long arithmetic, so
        // three of the five levels advertised a rep faster than the one the
        // counter would actually accept.
        val shown = StrictnessProfile.all().associate { it.level to formatPreciseSeconds(it.minRepMillis) }

        assertEquals("0.35s", shown[1])
        assertEquals("0.45s", shown[2])
        assertEquals("0.6s", shown[3])
        assertEquals("0.75s", shown[4])
        assertEquals("0.9s", shown[5])
    }

    // ----------------------------------------------------------- time until

    @Test
    fun `expiry already past reads as now`() {
        assertEquals("now", formatTimeUntil(1_000L, now = 1_000L))
        assertEquals("now", formatTimeUntil(500L, now = 1_000L))
    }

    @Test
    fun `hours and minutes are both shown while there are hours left`() {
        val now = 1_700_000_000_000L
        assertEquals("in 21h 4m", formatTimeUntil(now + (21 * 60 + 4) * 60_000L, now))
    }

    @Test
    fun `under an hour drops the hours`() {
        val now = 1_700_000_000_000L
        assertEquals("in 4m", formatTimeUntil(now + 4 * 60_000L, now))
    }

    @Test
    fun `the last minute is named rather than shown as zero`() {
        val now = 1_700_000_000_000L
        assertEquals("in under a minute", formatTimeUntil(now + 30_000L, now))
    }
}
