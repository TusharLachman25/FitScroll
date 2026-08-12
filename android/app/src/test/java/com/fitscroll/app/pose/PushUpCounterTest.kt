package com.fitscroll.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the counter with synthetic descents.
 *
 * Each rep here is a bank credit, so an over-generous counter literally prints
 * screen time. The cheats worth defending against — half reps, arm waves and
 * sagging hips — each get a case.
 */
class PushUpCounterTest {

    private val standard = StrictnessProfile.forLevel(3) // down 90, up 156, body 150, 600ms
    private val casual = StrictnessProfile.forLevel(1) // down 115, up 145, body 115, 350ms
    private val brutal = StrictnessProfile.forLevel(5) // down 72, up 168, body 165, 900ms

    private val frameMillis = 33L // ~30fps, matching the analysis stream

    /**
     * Holds an angle for several frames so the exponential smoother converges,
     * mirroring how a real camera delivers the same pose repeatedly.
     * Returns the timestamp after the last frame.
     */
    private fun PushUpCounter.hold(
        elbow: Float,
        startAt: Long,
        body: Float = 180f,
        confidence: Float = 0.9f,
        frames: Int = 8,
    ): Long {
        var t = startAt
        repeat(frames) {
            onFrame(PoseMetrics(elbow, body, confidence), t)
            t += frameMillis
        }
        return t
    }

    private fun PushUpCounter.frame(
        elbow: Float,
        at: Long,
        body: Float = 180f,
        confidence: Float = 0.9f,
    ) = onFrame(PoseMetrics(elbow, body, confidence), at)

    // ----------------------------------------------------------- happy path

    @Test
    fun `a clean controlled rep counts`() {
        val counter = PushUpCounter(standard)

        val topDone = counter.hold(elbow = 175f, startAt = 0L) // lock out
        counter.hold(elbow = 70f, startAt = topDone) // descend to depth
        val result = counter.frame(elbow = 175f, at = topDone + 900L) // press back up

        assertEquals(1, counter.reps)
        assertTrue(result.repJustCounted)
        assertEquals(RepPhase.TOP, result.phase)
    }

    @Test
    fun `consecutive reps accumulate`() {
        val counter = PushUpCounter(standard)
        var t = counter.hold(elbow = 175f, startAt = 0L)

        repeat(5) {
            val bottomAt = t
            t = counter.hold(elbow = 70f, startAt = bottomAt)
            t = bottomAt + 900L
            counter.frame(elbow = 175f, at = t)
            t += frameMillis
        }

        assertEquals(5, counter.reps)
    }

    // --------------------------------------------------------- depth gating

    @Test
    fun `a half rep that never reaches depth does not count`() {
        val counter = PushUpCounter(standard)

        val topDone = counter.hold(elbow = 175f, startAt = 0L)
        // 120 degrees is a visible dip but short of the 90 the level demands.
        val bottomDone = counter.hold(elbow = 120f, startAt = topDone)
        counter.frame(elbow = 175f, at = bottomDone + 900L)

        assertEquals(0, counter.reps)
    }

    @Test
    fun `depth reported to the UI tracks progress toward the threshold`() {
        val counter = PushUpCounter(standard)
        val topDone = counter.hold(elbow = 175f, startAt = 0L)

        assertEquals(0f, counter.frame(elbow = 175f, at = topDone).depth, 0.01f)

        // Half way between the 156 lockout gate and the 90 depth gate.
        val halfway = (156f + 90f) / 2f
        counter.hold(elbow = halfway, startAt = topDone)
        assertEquals(0.5f, counter.frame(halfway, at = topDone + 300L).depth, 0.08f)
    }

    // -------------------------------------------------------- timing gating

    @Test
    fun `a rep faster than the minimum duration is rejected`() {
        val counter = PushUpCounter(standard)

        val topDone = counter.hold(elbow = 175f, startAt = 0L)
        counter.hold(elbow = 70f, startAt = topDone)
        val result = counter.frame(elbow = 175f, at = topDone + 200L) // 200ms round trip

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
        assertTrue(result.rejection!!.contains("fast", ignoreCase = true))
    }

    @Test
    fun `an arm swiped across the camera in one frame banks nothing`() {
        val counter = PushUpCounter(standard)
        val topDone = counter.hold(elbow = 175f, startAt = 0L)

        // Lockout to full depth and back inside two frames — the shape of a
        // hand waved past the lens rather than a body moving.
        counter.frame(elbow = 60f, at = topDone)
        val result = counter.frame(elbow = 175f, at = topDone + frameMillis)

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
    }

    // ---------------------------------------------------------- form gating

    @Test
    fun `sagging hips void the rep`() {
        val counter = PushUpCounter(standard)

        val topDone = counter.hold(elbow = 175f, startAt = 0L)
        // Body line at 120 is well under the 150 this level tolerates.
        counter.hold(elbow = 70f, startAt = topDone, body = 120f)
        val result = counter.frame(elbow = 175f, at = topDone + 900L, body = 120f)

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
        assertTrue(result.rejection!!.contains("straight", ignoreCase = true))
    }

    @Test
    fun `form broken while resting at the top does not void the next rep`() {
        val counter = PushUpCounter(standard)

        var t = counter.hold(elbow = 175f, startAt = 0L)
        t = counter.hold(elbow = 175f, startAt = t, body = 100f) // slouching between sets
        t = counter.hold(elbow = 70f, startAt = t) // clean descent
        counter.frame(elbow = 175f, at = t + 900L)

        assertEquals(1, counter.reps)
    }

    @Test
    fun `form is reported to the UI so the overlay can flag it live`() {
        val counter = PushUpCounter(standard)
        val topDone = counter.hold(elbow = 175f, startAt = 0L)

        val sagging = counter.frame(elbow = 140f, at = topDone, body = 100f)

        assertFalse(sagging.formOk)
        assertEquals(Coaching.STRAIGHTEN_BODY, sagging.coaching)
    }

    // ------------------------------------------------------ tracking losses

    @Test
    fun `losing the subject preserves the count but voids the in-flight rep`() {
        val counter = PushUpCounter(standard)
        var t = counter.hold(elbow = 175f, startAt = 0L)
        t = counter.hold(elbow = 70f, startAt = t)
        counter.frame(elbow = 175f, at = t + 900L)
        assertEquals(1, counter.reps)

        // Camera loses them mid-descent.
        t = counter.hold(elbow = 70f, startAt = t + 933L)
        val lost = counter.frame(elbow = 70f, at = t, confidence = 0.1f)

        assertEquals(1, counter.reps) // banked reps survive
        assertEquals(RepPhase.SEARCHING, lost.phase)
        assertEquals(Coaching.FINDING_YOU, lost.coaching)

        // Coming back up now must not be credited, because the descent was
        // never observed end to end.
        counter.frame(elbow = 175f, at = t + 500L)
        assertEquals(1, counter.reps)
    }

    @Test
    fun `a null pose is treated as a tracking loss`() {
        val counter = PushUpCounter(standard)
        counter.hold(elbow = 175f, startAt = 0L)

        val result = counter.onFrame(metrics = null, nowMillis = 500L)

        assertEquals(RepPhase.SEARCHING, result.phase)
        assertEquals(0, result.reps)
    }

    // ------------------------------------------------------ strictness dial

    @Test
    fun `the same shallow rep counts on casual and not on standard`() {
        // A 100-degree bend: past casual's 115 gate, short of standard's 90.
        fun runRep(profile: StrictnessProfile): Int {
            val counter = PushUpCounter(profile)
            val topDone = counter.hold(elbow = 175f, startAt = 0L)
            counter.hold(elbow = 100f, startAt = topDone)
            counter.frame(elbow = 175f, at = topDone + 1200L)
            return counter.reps
        }

        assertEquals(1, runRep(casual))
        assertEquals(0, runRep(standard))
    }

    @Test
    fun `brutal demands a slower rep than standard accepts`() {
        fun runRep(profile: StrictnessProfile): Int {
            val counter = PushUpCounter(profile)
            val topDone = counter.hold(elbow = 178f, startAt = 0L)
            counter.hold(elbow = 65f, startAt = topDone)
            counter.frame(elbow = 178f, at = topDone + 700L) // 700ms round trip
            return counter.reps
        }

        assertEquals(1, runRep(standard)) // clears the 600ms floor
        assertEquals(0, runRep(brutal)) // misses the 900ms floor
    }

    @Test
    fun `changing strictness mid-session abandons the rep in progress`() {
        val counter = PushUpCounter(standard)
        val topDone = counter.hold(elbow = 175f, startAt = 0L)
        counter.hold(elbow = 70f, startAt = topDone)

        counter.setProfile(brutal)
        val result = counter.frame(elbow = 175f, at = topDone + 2000L)

        assertEquals(0, counter.reps)
        assertEquals(RepPhase.TOP, result.phase) // re-acquired at lockout, not credited
    }

    // ---------------------------------------------------------------- reset

    @Test
    fun `reset clears the count and the state machine`() {
        val counter = PushUpCounter(standard)
        val topDone = counter.hold(elbow = 175f, startAt = 0L)
        counter.hold(elbow = 70f, startAt = topDone)
        counter.frame(elbow = 175f, at = topDone + 900L)
        assertEquals(1, counter.reps)

        counter.reset()

        assertEquals(0, counter.reps)
        assertEquals(RepPhase.SEARCHING, counter.onFrame(null, 0L).phase)
    }
}
