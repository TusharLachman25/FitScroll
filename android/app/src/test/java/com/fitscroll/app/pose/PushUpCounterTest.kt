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
 * screen time. The cheats worth defending against - half reps, arm waves and
 * sagging hips - each get a case, and so does the opposite failure: a clean rep
 * being thrown away for camera noise.
 */
class PushUpCounterTest {

    private val standard = StrictnessProfile.forLevel(3) // down 92, up 152, body 128, grace 800ms, 600ms
    private val casual = StrictnessProfile.forLevel(1) // down 115, up 142, body 100, grace 1500ms, 350ms
    private val brutal = StrictnessProfile.forLevel(5) // down 72, up 162, body 148, grace 350ms, 900ms

    private val frameMillis = 33L // ~30fps, matching the analysis stream

    /**
     * Feeds a held pose for [durationMillis] at camera frame rate, which is how
     * a real descent arrives. Returns the timestamp just past the last frame.
     */
    private fun PushUpCounter.holdFor(
        elbow: Float,
        startAt: Long,
        durationMillis: Long,
        body: Float = 180f,
        confidence: Float = 0.9f,
        bodyConfidence: Float = 0.9f,
    ): Long {
        var t = startAt
        val end = startAt + durationMillis
        while (t <= end) {
            onFrame(PoseMetrics(elbow, body, confidence, bodyConfidence), t)
            t += frameMillis
        }
        return t
    }

    private fun PushUpCounter.frame(
        elbow: Float,
        at: Long,
        body: Float = 180f,
        confidence: Float = 0.9f,
        bodyConfidence: Float = 0.9f,
    ) = onFrame(PoseMetrics(elbow, body, confidence, bodyConfidence), at)

    /** Long enough for the exponential angle smoother to converge. */
    private val settle = 300L

    // ----------------------------------------------------------- happy path

    @Test
    fun `a clean controlled rep counts`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 500L)
        val result = counter.frame(elbow = 175f, at = top + 900L)

        assertEquals(1, counter.reps)
        assertTrue(result.repJustCounted)
        assertEquals(RepPhase.TOP, result.phase)
    }

    @Test
    fun `consecutive reps accumulate`() {
        val counter = PushUpCounter(standard)
        var t = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)

        repeat(5) {
            val descentAt = t
            counter.holdFor(elbow = 70f, startAt = descentAt, durationMillis = 500L)
            t = descentAt + 900L
            counter.frame(elbow = 175f, at = t)
            t += frameMillis
        }

        assertEquals(5, counter.reps)
    }

    // --------------------------------------------------------- depth gating

    @Test
    fun `a half rep that never reaches depth does not count`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        // 120 degrees is a visible dip but short of the 90 the level demands.
        counter.holdFor(elbow = 120f, startAt = top, durationMillis = 500L)
        counter.frame(elbow = 175f, at = top + 900L)

        assertEquals(0, counter.reps)
    }

    @Test
    fun `depth reported to the UI tracks progress toward the threshold`() {
        val counter = PushUpCounter(standard)
        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)

        assertEquals(0f, counter.frame(elbow = 175f, at = top).depth, 0.01f)

        val halfway = (standard.upElbowAngle + standard.downElbowAngle) / 2f
        counter.holdFor(elbow = halfway, startAt = top, durationMillis = settle)
        assertEquals(0.5f, counter.frame(halfway, at = top + 400L).depth, 0.08f)
    }

    // -------------------------------------------------------- timing gating

    @Test
    fun `a rep faster than the minimum duration is rejected`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 100L)
        val result = counter.frame(elbow = 175f, at = top + 200L)

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
        assertTrue(result.rejection!!.contains("fast", ignoreCase = true))
    }

    @Test
    fun `an arm swiped across the camera in one frame banks nothing`() {
        val counter = PushUpCounter(standard)
        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)

        // Lockout to full depth and back inside two frames - the shape of a
        // hand waved past the lens rather than a body moving.
        counter.frame(elbow = 60f, at = top)
        val result = counter.frame(elbow = 175f, at = top + frameMillis)

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
    }

    // ---------------------------------------------------------- form gating

    @Test
    fun `sustained hip sag voids the rep`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        // A body line of 110 is well under the 132 this level tolerates, held
        // for the entire rep rather than a stray frame.
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 1_200L, body = 110f)
        val result = counter.frame(elbow = 175f, at = top + 1_400L, body = 110f)

        assertEquals(0, counter.reps)
        assertNotNull(result.rejection)
        assertTrue(result.rejection!!.contains("hips", ignoreCase = true))
    }

    @Test
    fun `a few noisy frames do not void an otherwise clean rep`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        var t = counter.holdFor(elbow = 70f, startAt = top, durationMillis = 300L)

        // Three frames of hip jitter, ~100ms, far inside the 800ms grace. This
        // is the shape of ML Kit noise on a motionless subject, and latching on
        // it was rejecting real push-ups.
        repeat(3) {
            counter.frame(elbow = 70f, at = t, body = 100f)
            t += frameMillis
        }

        counter.holdFor(elbow = 70f, startAt = t, durationMillis = 200L)
        counter.frame(elbow = 175f, at = top + 900L)

        assertEquals(1, counter.reps)
    }

    @Test
    fun `form is not judged when the legs are not confidently visible`() {
        val counter = PushUpCounter(standard)

        // Body angle is nonsense, but so is the confidence behind it - the
        // model is guessing at a knee that is outside the frame.
        val top = counter.holdFor(
            elbow = 175f, startAt = 0L, durationMillis = settle,
            body = 90f, bodyConfidence = 0.1f,
        )
        counter.holdFor(
            elbow = 70f, startAt = top, durationMillis = 600L,
            body = 90f, bodyConfidence = 0.1f,
        )
        val result = counter.frame(
            elbow = 175f, at = top + 900L,
            body = 90f, bodyConfidence = 0.1f,
        )

        assertEquals(1, counter.reps)
        assertFalse(result.formJudged)
        assertTrue(result.formOk) // reported as fine, because it was not assessed
    }

    @Test
    fun `form broken while resting at the top does not void the next rep`() {
        val counter = PushUpCounter(standard)

        var t = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        t = counter.holdFor(elbow = 175f, startAt = t, durationMillis = 1_500L, body = 100f)
        val descentAt = t
        counter.holdFor(elbow = 70f, startAt = descentAt, durationMillis = 500L)
        counter.frame(elbow = 175f, at = descentAt + 900L)

        assertEquals(1, counter.reps)
    }

    @Test
    fun `form is reported to the UI so the overlay can flag it live`() {
        val counter = PushUpCounter(standard)
        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)

        val sagging = counter.frame(elbow = 140f, at = top, body = 100f)

        assertFalse(sagging.formOk)
        assertTrue(sagging.formJudged)
        assertEquals(Coaching.STRAIGHTEN_BODY, sagging.coaching)
    }

    @Test
    fun `a tracking gap does not burn the form budget`() {
        val counter = PushUpCounter(standard)

        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 300L)

        // A ten second gap with bad form on the resuming frame. Charging the
        // whole gap to the form budget would void a rep for time in which
        // nobody was even being tracked.
        counter.frame(elbow = 70f, at = top + 10_000L, body = 100f)
        counter.frame(elbow = 175f, at = top + 10_100L)

        assertEquals(1, counter.reps)
    }

    // ------------------------------------------------------ tracking losses

    @Test
    fun `losing the subject preserves the count but voids the in-flight rep`() {
        val counter = PushUpCounter(standard)
        var t = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = t, durationMillis = 500L)
        counter.frame(elbow = 175f, at = t + 900L)
        assertEquals(1, counter.reps)

        // Camera loses them mid-descent.
        t = counter.holdFor(elbow = 70f, startAt = t + 933L, durationMillis = settle)
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
        counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)

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
            val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
            counter.holdFor(elbow = 100f, startAt = top, durationMillis = 600L)
            counter.frame(elbow = 175f, at = top + 1_200L)
            return counter.reps
        }

        assertEquals(1, runRep(casual))
        assertEquals(0, runRep(standard))
    }

    @Test
    fun `brutal demands a slower rep than standard accepts`() {
        fun runRep(profile: StrictnessProfile): Int {
            val counter = PushUpCounter(profile)
            val top = counter.holdFor(elbow = 178f, startAt = 0L, durationMillis = settle)
            counter.holdFor(elbow = 65f, startAt = top, durationMillis = 400L)
            counter.frame(elbow = 178f, at = top + 700L) // 700ms round trip
            return counter.reps
        }

        assertEquals(1, runRep(standard)) // clears the 600ms floor
        assertEquals(0, runRep(brutal)) // misses the 900ms floor
    }

    @Test
    fun `middling tracking confidence counts on every level`() {
        // Confidence used to scale with the dial, so the same clean rep counted
        // on level 1 and silently vanished on level 5. Looking down at the
        // floor is enough to drop ML Kit's likelihood across every landmark,
        // which meant the top levels were quietly demanding a better view
        // rather than a better push-up.
        fun runRep(profile: StrictnessProfile): Int {
            val counter = PushUpCounter(profile)
            val top = counter.holdFor(
                elbow = 178f, startAt = 0L, durationMillis = settle,
                confidence = 0.45f, bodyConfidence = 0.45f,
            )
            counter.holdFor(
                elbow = 65f, startAt = top, durationMillis = 900L,
                confidence = 0.45f, bodyConfidence = 0.45f,
            )
            counter.frame(
                elbow = 178f, at = top + 1_200L,
                confidence = 0.45f, bodyConfidence = 0.45f,
            )
            return counter.reps
        }

        assertEquals(1, runRep(casual))
        assertEquals(1, runRep(brutal))
    }

    @Test
    fun `casual tolerates a body line that brutal rejects`() {
        fun runRep(profile: StrictnessProfile): Int {
            val counter = PushUpCounter(profile)
            val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle, body = 140f)
            counter.holdFor(elbow = 60f, startAt = top, durationMillis = 1_200L, body = 140f)
            counter.frame(elbow = 175f, at = top + 1_400L, body = 140f)
            return counter.reps
        }

        assertEquals(1, runRep(casual)) // 140 clears casual's 100
        assertEquals(0, runRep(brutal)) // and misses brutal's 155
    }

    @Test
    fun `changing strictness mid-session abandons the rep in progress`() {
        val counter = PushUpCounter(standard)
        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 500L)

        counter.setProfile(brutal)
        val result = counter.frame(elbow = 175f, at = top + 2_000L)

        assertEquals(0, counter.reps)
        assertEquals(RepPhase.TOP, result.phase) // re-acquired at lockout, not credited
    }

    // ---------------------------------------------------------------- reset

    @Test
    fun `reset clears the count and the state machine`() {
        val counter = PushUpCounter(standard)
        val top = counter.holdFor(elbow = 175f, startAt = 0L, durationMillis = settle)
        counter.holdFor(elbow = 70f, startAt = top, durationMillis = 500L)
        counter.frame(elbow = 175f, at = top + 900L)
        assertEquals(1, counter.reps)

        counter.reset()

        assertEquals(0, counter.reps)
        assertEquals(RepPhase.SEARCHING, counter.onFrame(null, 0L).phase)
    }
}
