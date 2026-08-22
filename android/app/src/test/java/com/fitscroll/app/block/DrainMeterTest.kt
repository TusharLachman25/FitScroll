package com.fitscroll.app.block

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-second accounting for a live drain.
 *
 * This is the arithmetic the whole app rests on: an app is blocked by spending
 * the bank down in real time, so a meter that drifts is a bank that lies. The
 * cases worth pinning down are the ones a real phone produces and a tidy test
 * would not — a tick that lands late, a thread that stalled, and the part of a
 * second still on the meter when the user walks away mid-scroll.
 */
class DrainMeterTest {

    /** An arbitrary uptime to start from; only the deltas matter. */
    private val start = 5_000L

    private fun meter() = DrainMeter().apply { start(start) }

    // -------------------------------------------------------------- charging

    @Test
    fun `an exact second charges a second`() {
        assertEquals(1, meter().charge(start + 1_000L))
    }

    @Test
    fun `a tick that has not made a second yet charges nothing`() {
        assertEquals(0, meter().charge(start + 999L))
    }

    @Test
    fun `the part second is carried rather than dropped`() {
        // postDelayed re-arms only after the body has run, so every tick is a
        // little over a second. Truncating that remainder each time is how the
        // meter used to hand back most of a second per second.
        val meter = meter()
        var charged = 0
        var now = start

        repeat(10) {
            now += 1_100L
            charged += meter.charge(now)
        }

        assertEquals(11, charged)
    }

    @Test
    fun `carrying does not overcharge a steady second`() {
        val meter = meter()
        var charged = 0
        var now = start

        repeat(60) {
            now += 1_000L
            charged += meter.charge(now)
        }

        assertEquals(60, charged)
    }

    @Test
    fun `a stalled thread is charged for the time it really took`() {
        // The old meter charged a flat second per tick, so a phone that
        // stuttered handed out the difference as free screen time.
        assertEquals(4, meter().charge(start + 4_200L))
    }

    @Test
    fun `no single tick may charge past the ceiling`() {
        val meter = DrainMeter(maxCatchUpMillis = 5_000L).apply { start(start) }
        assertEquals(5, meter.charge(start + 60_000L))
    }

    @Test
    fun `a clock that did not move charges nothing rather than going negative`() {
        val meter = meter()
        assertEquals(0, meter.charge(start))
        assertEquals(0, meter.charge(start - 10_000L))
    }

    @Test
    fun `a meter that was never started charges nothing`() {
        assertEquals(0, DrainMeter().charge(start + 10_000L))
    }

    // -------------------------------------------------------------- settling

    @Test
    fun `stopping charges the whole seconds still on the meter`() {
        // Two and a half seconds after the last charge: the two are owed
        // outright, and the half rounds up.
        assertEquals(3, meter().settle(start + 2_500L))
    }

    @Test
    fun `a part second under half is not charged for`() {
        assertEquals(2, meter().settle(start + 2_400L))
    }

    @Test
    fun `the carried remainder counts toward the final second`() {
        val meter = meter()
        // Leaves 600ms carried.
        assertEquals(1, meter.charge(start + 1_600L))
        // 200ms more makes 800ms outstanding, which rounds to a second.
        assertEquals(1, meter.settle(start + 1_800L))
    }

    @Test
    fun `a session shorter than half a second costs nothing`() {
        assertEquals(0, meter().settle(start + 400L))
    }

    @Test
    fun `settling twice does not charge twice`() {
        val meter = meter()
        assertEquals(3, meter.settle(start + 3_000L))
        assertEquals(0, meter.settle(start + 9_000L))
    }

    @Test
    fun `restarting begins from the new instant, not the old one`() {
        val meter = meter()
        meter.settle(start + 3_000L)
        meter.start(start + 600_000L)
        assertEquals(1, meter.charge(start + 601_000L))
    }

    @Test
    fun `a stopped meter reports itself stopped`() {
        val meter = meter()
        assert(meter.isRunning)
        meter.settle(start + 1_000L)
        assert(!meter.isRunning)
    }

    // ------------------------------------------------------------- end to end

    @Test
    fun `a minute of scrolling in dipped-in sessions still costs a minute`() {
        // Six ten-second visits, each ending between ticks. Every one of those
        // endings used to forgive up to two seconds, so the bank came out
        // ahead of the scrolling by a noticeable margin over a day.
        var charged = 0
        var now = start
        val meter = DrainMeter()

        repeat(6) {
            meter.start(now)
            repeat(9) {
                now += 1_000L
                charged += meter.charge(now)
            }
            // Leaves the session 700ms into the tenth second.
            now += 700L
            charged += meter.settle(now)
            // Away for a while; none of this is chargeable.
            now += 45_000L
        }

        assertEquals(60, charged)
    }
}
