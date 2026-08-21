package com.fitscroll.app.block

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settling a drain that was cut short by the process being killed.
 *
 * The gap is measured on the wall clock, which is the only clock that survives
 * a reboot and also the only one a user can move, so the awkward cases here are
 * about refusing to trust it too far.
 */
class DrainMathTest {

    private val cap = 120
    private val start = 1_700_000_000_000L

    private fun pending(lastCharged: Long) = PendingDrain("com.instagram.android", lastCharged)

    private fun owed(gapMillis: Long, capSeconds: Int = cap) = DrainMath.reconcileSeconds(
        pending = pending(start),
        nowWall = start + gapMillis,
        capSeconds = capSeconds,
    )

    @Test
    fun `a short interruption is charged in full`() {
        assertEquals(8, owed(8_000L))
    }

    @Test
    fun `part seconds are rounded down, never up`() {
        assertEquals(3, owed(3_900L))
    }

    @Test
    fun `no measurable gap costs nothing`() {
        assertEquals(0, owed(0L))
        assertEquals(0, owed(999L))
    }

    @Test
    fun `a long interruption is capped rather than trusted`() {
        assertEquals(cap, owed(6L * 60L * 60L * 1000L))
    }

    @Test
    fun `the cap is the ceiling, not the charge`() {
        assertEquals(30, owed(30_000L, capSeconds = 120))
        assertEquals(10, owed(30_000L, capSeconds = 10))
    }

    @Test
    fun `a clock moved backwards is worth nothing rather than negative`() {
        // Charging a negative number would refund screen time for turning the
        // clock back, which is precisely the wrong incentive.
        assertEquals(0, owed(-60_000L))
    }

    @Test
    fun `a clock moved far forward cannot empty the bank`() {
        assertEquals(cap, owed(365L * 24L * 60L * 60L * 1000L))
    }
}
