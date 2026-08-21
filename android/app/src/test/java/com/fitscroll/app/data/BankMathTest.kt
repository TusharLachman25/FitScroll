package com.fitscroll.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bank is the one place where a bug silently hands out — or steals — screen
 * time, so the rules are pinned down here rather than verified by hand on a
 * device 24 hours later.
 */
class BankMathTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val cap = 1440 * 60 // 24h of screen time, the default ceiling

    private fun hoursAgo(h: Long) = now - h * hour

    // ------------------------------------------------------------- earning

    @Test
    fun `one rep banks exactly one minute`() {
        val outcome = BankMath.earn(emptyList(), reps = 1, now = now, capSeconds = cap)

        assertEquals(60, outcome.grantedSeconds)
        assertEquals(0, outcome.wastedSeconds)
        assertEquals(60, BankMath.balanceSeconds(outcome.credits, now))
    }

    @Test
    fun `twenty reps bank twenty minutes`() {
        val outcome = BankMath.earn(emptyList(), reps = 20, now = now, capSeconds = cap)

        assertEquals(20 * 60, BankMath.balanceSeconds(outcome.credits, now))
    }

    @Test
    fun `earning zero reps is a no-op`() {
        val existing = listOf(Credit(now, 300))

        val outcome = BankMath.earn(existing, reps = 0, now = now, capSeconds = cap)

        assertEquals(0, outcome.grantedSeconds)
        assertEquals(300, BankMath.balanceSeconds(outcome.credits, now))
    }

    // -------------------------------------------------------------- expiry

    @Test
    fun `credit just under twenty four hours old is still spendable`() {
        val credits = listOf(Credit(earnedAt = now - (24 * hour - 1000), remainingSeconds = 600))

        assertEquals(600, BankMath.balanceSeconds(credits, now))
    }

    @Test
    fun `credit at exactly twenty four hours has expired`() {
        val credits = listOf(Credit(earnedAt = now - 24 * hour, remainingSeconds = 600))

        assertEquals(0, BankMath.balanceSeconds(credits, now))
    }

    @Test
    fun `expiry only removes the stale batch, not the whole bank`() {
        val credits = listOf(
            Credit(earnedAt = hoursAgo(30), remainingSeconds = 600), // dead
            Credit(earnedAt = hoursAgo(2), remainingSeconds = 300), // alive
        )

        assertEquals(300, BankMath.balanceSeconds(credits, now))
        assertEquals(1, BankMath.purge(credits, now).size)
    }

    // ------------------------------------------------------------ spending

    @Test
    fun `spending drains the oldest credit first`() {
        val credits = listOf(
            Credit(earnedAt = hoursAgo(1), remainingSeconds = 120), // newer
            Credit(earnedAt = hoursAgo(20), remainingSeconds = 120), // older, expires sooner
        )

        val outcome = BankMath.spend(credits, seconds = 120, now = now)

        assertEquals(120, outcome.spentSeconds)
        assertEquals(1, outcome.credits.size)
        // The surviving credit must be the newer one; draining it first would
        // have let the older batch expire unspent.
        assertEquals(hoursAgo(1), outcome.credits.single().earnedAt)
    }

    @Test
    fun `spending can span multiple credits`() {
        val credits = listOf(
            Credit(earnedAt = hoursAgo(5), remainingSeconds = 60),
            Credit(earnedAt = hoursAgo(3), remainingSeconds = 60),
            Credit(earnedAt = hoursAgo(1), remainingSeconds = 60),
        )

        val outcome = BankMath.spend(credits, seconds = 150, now = now)

        assertEquals(150, outcome.spentSeconds)
        assertEquals(30, BankMath.balanceSeconds(outcome.credits, now))
    }

    @Test
    fun `spending more than the balance reports the shortfall`() {
        val credits = listOf(Credit(earnedAt = now, remainingSeconds = 45))

        val outcome = BankMath.spend(credits, seconds = 60, now = now)

        // Only 45s existed, so only 45s can be charged. The caller reads the
        // gap as "the bank just ran dry" and raises the lock screen.
        assertEquals(45, outcome.spentSeconds)
        assertTrue(outcome.credits.isEmpty())
    }

    @Test
    fun `expired credits cannot be spent`() {
        val credits = listOf(Credit(earnedAt = hoursAgo(25), remainingSeconds = 600))

        val outcome = BankMath.spend(credits, seconds = 60, now = now)

        assertEquals(0, outcome.spentSeconds)
    }

    @Test
    fun `a partly spent credit keeps its original earned timestamp`() {
        val earned = hoursAgo(10)
        val credits = listOf(Credit(earnedAt = earned, remainingSeconds = 300))

        val outcome = BankMath.spend(credits, seconds = 100, now = now)

        // Expiry must stay anchored to when the reps were done. Refreshing the
        // timestamp on spend would let someone extend minutes indefinitely by
        // dipping into Instagram for a second before each deadline.
        assertEquals(earned, outcome.credits.single().earnedAt)
        assertEquals(200, outcome.credits.single().remainingSeconds)
    }

    // ------------------------------------------------------------ the cap

    @Test
    fun `earning is clamped at the cap and reports the waste`() {
        val smallCap = 10 * 60 // 10 minutes
        val existing = listOf(Credit(earnedAt = now, remainingSeconds = 8 * 60))

        val outcome = BankMath.earn(existing, reps = 5, now = now, capSeconds = smallCap)

        assertEquals(2 * 60, outcome.grantedSeconds)
        assertEquals(3 * 60, outcome.wastedSeconds)
        assertEquals(smallCap, BankMath.balanceSeconds(outcome.credits, now))
    }

    @Test
    fun `a full bank grants nothing`() {
        val smallCap = 5 * 60
        val existing = listOf(Credit(earnedAt = now, remainingSeconds = smallCap))

        val outcome = BankMath.earn(existing, reps = 10, now = now, capSeconds = smallCap)

        assertEquals(0, outcome.grantedSeconds)
        assertEquals(10 * 60, outcome.wastedSeconds)
    }

    @Test
    fun `expired credits free up room under the cap`() {
        val smallCap = 10 * 60
        val existing = listOf(Credit(earnedAt = hoursAgo(25), remainingSeconds = smallCap))

        val outcome = BankMath.earn(existing, reps = 10, now = now, capSeconds = smallCap)

        assertEquals(smallCap, outcome.grantedSeconds)
    }

    // ------------------------------------------------------------ readouts

    @Test
    fun `next expiry tracks the oldest live credit`() {
        val credits = listOf(
            Credit(earnedAt = hoursAgo(1), remainingSeconds = 60),
            Credit(earnedAt = hoursAgo(20), remainingSeconds = 60),
        )

        assertEquals(hoursAgo(20) + BankMath.EXPIRY_MILLIS, BankMath.nextExpiryAt(credits, now))
    }

    @Test
    fun `next expiry is null on an empty bank`() {
        assertNull(BankMath.nextExpiryAt(emptyList(), now))
        assertNull(BankMath.nextExpiryAt(listOf(Credit(hoursAgo(30), 60)), now))
    }

    @Test
    fun `expiring within an hour counts only the batches about to die`() {
        val credits = listOf(
            Credit(earnedAt = hoursAgo(23), remainingSeconds = 120), // 1h of life left
            Credit(earnedAt = hoursAgo(2), remainingSeconds = 300), // 22h of life left
        )

        assertEquals(120, BankMath.expiringWithin(credits, now, hour))
    }

    // ----------------------------------------------------- end-to-end rule

    @Test
    fun `a full day cycle - earn, partly spend, let the rest expire`() {
        var credits = BankMath.earn(emptyList(), reps = 30, now = now, capSeconds = cap).credits
        assertEquals(30 * 60, BankMath.balanceSeconds(credits, now))

        val afterScrolling = now + 2 * hour
        credits = BankMath.spend(credits, seconds = 18 * 60, now = afterScrolling).credits
        assertEquals(12 * 60, BankMath.balanceSeconds(credits, afterScrolling))

        // Next day: the unspent 12 minutes are gone, not carried forward.
        val nextDay = now + 25 * hour
        assertEquals(0, BankMath.balanceSeconds(credits, nextDay))
    }

    // ------------------------------------------------------ a moved clock

    @Test
    fun `a credit stamped in the future expires a day from now, not never`() {
        // Winding the clock forward, banking, and winding it back leaves
        // earnedAt ahead of now, so `now - earnedAt` is negative and sits under
        // the 24h test for as long as the credit exists. Clamping bounds it to
        // one ordinary day instead.
        val fromTheFuture = listOf(Credit(earnedAt = now + 48 * hour, remainingSeconds = 600))

        assertEquals(600, BankMath.balanceSeconds(fromTheFuture, now))
        assertEquals(now + BankMath.EXPIRY_MILLIS, BankMath.nextExpiryAt(fromTheFuture, now))

        val purged = BankMath.purge(fromTheFuture, now)
        assertEquals(0, BankMath.balanceSeconds(purged, now + 25 * hour))
    }

    @Test
    fun `a future credit is pulled back rather than thrown away`() {
        // Losing the minutes outright would punish someone whose phone simply
        // had the wrong time when they did the push-ups.
        val purged = BankMath.purge(listOf(Credit(now + hour, 300)), now)

        assertEquals(1, purged.size)
        assertEquals(now, purged.single().earnedAt)
        assertEquals(300, purged.single().remainingSeconds)
    }

    @Test
    fun `clamping leaves ordinary credits untouched`() {
        val credits = listOf(Credit(hoursAgo(3), 600), Credit(hoursAgo(20), 300))

        assertEquals(credits, BankMath.purge(credits, now))
    }

    @Test
    fun `a future credit is spent like any other`() {
        val outcome = BankMath.spend(listOf(Credit(now + 10 * hour, 600)), seconds = 60, now = now)

        assertEquals(60, outcome.spentSeconds)
        assertEquals(540, outcome.credits.single().remainingSeconds)
        assertEquals(now, outcome.credits.single().earnedAt)
    }
}
