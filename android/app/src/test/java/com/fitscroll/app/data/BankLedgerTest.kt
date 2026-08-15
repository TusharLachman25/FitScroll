package com.fitscroll.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold is what makes a synced bank trustworthy: every device has to derive
 * the same balance from the same events, whatever order they arrived in and
 * whichever device was offline at the time.
 */
class BankLedgerTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val cap = 1440 * 60

    private fun hoursAgo(h: Long) = now - h * hour

    private fun earn(id: String, minutes: Int, at: Long) =
        BankEvent(id, BankEvent.Kind.EARN, minutes * 60, at)

    private fun spend(id: String, seconds: Int, at: Long) =
        BankEvent(id, BankEvent.Kind.SPEND, seconds, at)

    @Test
    fun `an empty log is an empty bank`() {
        assertEquals(0, BankLedger.balanceSeconds(emptyList(), now, cap))
    }

    @Test
    fun `earning then spending leaves the difference`() {
        val events = listOf(
            earn("a", 20, hoursAgo(3)),
            spend("b", 5 * 60, hoursAgo(2)),
        )

        assertEquals(15 * 60, BankLedger.balanceSeconds(events, now, cap))
    }

    @Test
    fun `the fold is independent of the order events arrive in`() {
        val events = listOf(
            earn("a", 10, hoursAgo(5)),
            spend("b", 200, hoursAgo(4)),
            earn("c", 5, hoursAgo(3)),
            spend("d", 400, hoursAgo(1)),
        )

        val forwards = BankLedger.balanceSeconds(events, now, cap)
        val backwards = BankLedger.balanceSeconds(events.reversed(), now, cap)
        val shuffled = BankLedger.balanceSeconds(events.shuffled(), now, cap)

        assertEquals(15 * 60 - 600, forwards)
        assertEquals(forwards, backwards)
        assertEquals(forwards, shuffled)
    }

    @Test
    fun `simultaneous events fold identically on both devices`() {
        // Two phones record at the very same millisecond. Without a tie-break
        // each would replay in its own arrival order and report a different
        // balance to its user.
        val sameInstant = hoursAgo(2)
        val a = spend("aaaa", 300, sameInstant)
        val b = spend("bbbb", 120, sameInstant)
        val base = earn("earn", 30, hoursAgo(3))

        val deviceOne = BankLedger.balanceSeconds(listOf(base, a, b), now, cap)
        val deviceTwo = BankLedger.balanceSeconds(listOf(base, b, a), now, cap)

        assertEquals(deviceOne, deviceTwo)
        assertEquals(30 * 60 - 420, deviceOne)
    }

    @Test
    fun `a duplicated event is only counted once`() {
        val credit = earn("dupe", 10, hoursAgo(1))

        val once = BankLedger.balanceSeconds(listOf(credit), now, cap)
        val twice = BankLedger.balanceSeconds(listOf(credit, credit.copy()), now, cap)

        assertEquals(once, twice)
        assertEquals(10 * 60, twice)
    }

    @Test
    fun `expiry is anchored to when minutes were earned, not when they synced`() {
        // Banked 25 hours ago on a phone that has only just come back online.
        // Uploading late must not extend the shelf life.
        val events = listOf(earn("stale", 30, hoursAgo(25)))

        assertEquals(0, BankLedger.balanceSeconds(events, now, cap))
    }

    @Test
    fun `a spend can only consume credits that were live when it happened`() {
        val events = listOf(
            earn("old", 10, hoursAgo(30)), // dead well before the spend
            earn("new", 10, hoursAgo(2)),
            spend("s", 15 * 60, hoursAgo(1)),
        )

        // Only the newer 10 minutes existed at the time of the spend, so the
        // spend takes 10 and the overdraft is dropped rather than reaching back
        // to consume an already-expired credit.
        assertEquals(0, BankLedger.balanceSeconds(events, now, cap))
    }

    @Test
    fun `two devices overspending offline clamps at zero rather than going negative`() {
        val events = listOf(
            earn("e", 5, hoursAgo(3)),
            spend("phone", 5 * 60, hoursAgo(2)),
            spend("ipad", 5 * 60, hoursAgo(2)),
        )

        assertEquals(0, BankLedger.balanceSeconds(events, now, cap))
    }

    @Test
    fun `the cap is applied as of each earn, not at the end`() {
        val smallCap = 10 * 60
        val events = listOf(
            earn("a", 8, hoursAgo(5)),
            earn("b", 8, hoursAgo(4)), // only 2 minutes of room left at this point
        )

        assertEquals(smallCap, BankLedger.balanceSeconds(events, now, smallCap))
    }

    @Test
    fun `spending frees room under the cap for a later earn`() {
        val smallCap = 10 * 60
        val events = listOf(
            earn("a", 10, hoursAgo(5)),
            spend("b", 6 * 60, hoursAgo(4)),
            earn("c", 10, hoursAgo(3)),
        )

        assertEquals(smallCap, BankLedger.balanceSeconds(events, now, smallCap))
    }

    // --------------------------------------------------------------- merging

    @Test
    fun `merging keeps one copy of events present on both sides`() {
        val shared = earn("shared", 10, hoursAgo(2))
        val localOnly = spend("local", 60, hoursAgo(1))
        val remoteOnly = spend("remote", 120, hoursAgo(1))

        val merged = BankLedger.merge(
            local = listOf(shared, localOnly),
            remote = listOf(shared, remoteOnly),
        )

        assertEquals(3, merged.size)
        assertEquals(10 * 60 - 180, BankLedger.balanceSeconds(merged, now, cap))
    }

    @Test
    fun `merging is commutative`() {
        val local = listOf(earn("a", 10, hoursAgo(3)), spend("b", 90, hoursAgo(2)))
        val remote = listOf(earn("c", 4, hoursAgo(2)), spend("d", 30, hoursAgo(1)))

        assertEquals(
            BankLedger.balanceSeconds(BankLedger.merge(local, remote), now, cap),
            BankLedger.balanceSeconds(BankLedger.merge(remote, local), now, cap),
        )
    }

    // --------------------------------------------------------------- pruning

    @Test
    fun `pruning drops only events too old to matter`() {
        val events = listOf(
            earn("ancient", 10, hoursAgo(48)),
            earn("stale", 10, hoursAgo(26)),
            earn("live", 10, hoursAgo(2)),
        )

        val kept = BankLedger.prune(events, now)

        assertTrue(kept.none { it.id == "ancient" })
        assertTrue(kept.any { it.id == "live" })
        // Balance is unchanged by pruning, which is the property that makes it
        // safe to run at all.
        assertEquals(
            BankLedger.balanceSeconds(events, now, cap),
            BankLedger.balanceSeconds(kept, now, cap),
        )
    }

    @Test
    fun `signing out and back in restores the same balance`() {
        // Logout keeps nothing locally; the server ledger is append-only, so
        // signing back in and re-folding must land on the identical number.
        val serverLedger = listOf(
            earn("a", 25, hoursAgo(6)),
            spend("b", 9 * 60, hoursAgo(5)),
            earn("c", 12, hoursAgo(4)),
        )
        val beforeLogout = BankLedger.balanceSeconds(serverLedger, now, cap)

        val afterFreshLogin = BankLedger.balanceSeconds(
            BankLedger.merge(local = emptyList(), remote = serverLedger),
            now,
            cap,
        )

        assertEquals(beforeLogout, afterFreshLogin)
        assertEquals(28 * 60, afterFreshLogin)
    }
}
