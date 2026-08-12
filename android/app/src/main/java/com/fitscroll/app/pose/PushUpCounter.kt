package com.fitscroll.app.pose

/** Where in the movement the athlete currently is. */
enum class RepPhase { SEARCHING, TOP, BOTTOM }

/** Persistent on-screen coaching line. */
enum class Coaching(val message: String) {
    FINDING_YOU("Get your whole body in frame"),
    GET_SET("Arms straight — get set at the top"),
    GO_LOWER("Lower — bend those elbows"),
    PUSH_UP("Push all the way back up"),
    STRAIGHTEN_BODY("Straighten your back"),
}

data class CounterUpdate(
    val reps: Int,
    val phase: RepPhase,
    val coaching: Coaching,
    /** 0..1 progress toward the depth this strictness level demands. */
    val depth: Float,
    /** False while the body line is outside tolerance. Drives the overlay colour. */
    val formOk: Boolean,
    /** True only on the frame a rep landed, so the caller can buzz once. */
    val repJustCounted: Boolean,
    /** Why the last attempt did not count, held briefly so the UI can show it. */
    val rejection: String?,
)

/**
 * Counts push-ups from a stream of [PoseMetrics].
 *
 * Deliberately pure: no ML Kit, no Android, no clock of its own. The caller
 * supplies `nowMillis`, which makes every rule in here exercisable from a unit
 * test with a synthetic descent.
 *
 * A rep is the round trip TOP -> BOTTOM -> TOP, and it only banks a minute if
 * it clears all three gates for the active [StrictnessProfile]: it reached the
 * required depth, it took at least the minimum duration, and the body line
 * never broke tolerance at any point during the movement.
 */
class PushUpCounter(private var profile: StrictnessProfile) {

    var reps: Int = 0
        private set

    private var phase: RepPhase = RepPhase.SEARCHING
    private var descentStartedAt: Long = NOT_DESCENDING
    private var formBrokenThisRep = false
    private var smoothedElbow: Float? = null
    private var smoothedBody: Float? = null

    private var rejectionMessage: String? = null
    private var rejectionExpiresAt: Long = 0L

    /** Swaps strictness mid-session; the in-flight rep is abandoned. */
    fun setProfile(next: StrictnessProfile) {
        if (next.level == profile.level) return
        profile = next
        phase = RepPhase.SEARCHING
        descentStartedAt = NOT_DESCENDING
        formBrokenThisRep = false
    }

    fun reset() {
        reps = 0
        phase = RepPhase.SEARCHING
        descentStartedAt = NOT_DESCENDING
        formBrokenThisRep = false
        smoothedElbow = null
        smoothedBody = null
        rejectionMessage = null
        rejectionExpiresAt = 0L
    }

    /**
     * Feeds one frame. Pass null [metrics] when no usable skeleton was found.
     */
    fun onFrame(metrics: PoseMetrics?, nowMillis: Long): CounterUpdate {
        if (metrics == null || metrics.confidence < profile.minConfidence) {
            // Losing the subject must not wipe the rep count — people drop out
            // of frame between sets — but the in-flight rep is void, since we
            // cannot vouch for what happened while we could not see them.
            phase = RepPhase.SEARCHING
            descentStartedAt = NOT_DESCENDING
            smoothedElbow = null
            smoothedBody = null
            return update(Coaching.FINDING_YOU, depth = 0f, formOk = true, counted = false, now = nowMillis)
        }

        val elbow = Geometry.smooth(smoothedElbow, metrics.elbowAngle).also { smoothedElbow = it }
        val body = Geometry.smooth(smoothedBody, metrics.bodyLineAngle).also { smoothedBody = it }

        val formOk = body >= profile.minBodyLineAngle
        if (!formOk && phase != RepPhase.SEARCHING) formBrokenThisRep = true

        val depth = ((profile.upElbowAngle - elbow) /
            (profile.upElbowAngle - profile.downElbowAngle)).coerceIn(0f, 1f)

        var counted = false
        var coaching = Coaching.GET_SET

        when (phase) {
            RepPhase.SEARCHING -> {
                // Require a locked-out start so the first rep is measured from
                // the same place as every later one.
                if (elbow >= profile.upElbowAngle) {
                    phase = RepPhase.TOP
                    descentStartedAt = NOT_DESCENDING
                    formBrokenThisRep = false
                }
                coaching = Coaching.GET_SET
            }

            RepPhase.TOP -> {
                if (elbow < profile.upElbowAngle) {
                    // Descent has begun. Timing the rep from here rather than
                    // from the bottom means minRepMillis governs the whole
                    // movement, not just the push back up.
                    //
                    // The clock is started before the depth check, not instead
                    // of it: an arm swiped past the camera crosses both
                    // thresholds within a single frame, and if that case left
                    // the timer unset it would arrive at the top with no
                    // measurable duration and bank a free minute.
                    if (descentStartedAt == NOT_DESCENDING) {
                        descentStartedAt = nowMillis
                        formBrokenThisRep = !formOk
                    }
                    if (elbow <= profile.downElbowAngle) phase = RepPhase.BOTTOM
                } else {
                    // Returned to lockout without ever reaching depth: a partial
                    // rep. Discard the timer so it cannot be credited later.
                    descentStartedAt = NOT_DESCENDING
                }
                coaching = if (!formOk) Coaching.STRAIGHTEN_BODY else Coaching.GO_LOWER
            }

            RepPhase.BOTTOM -> {
                if (elbow >= profile.upElbowAngle) {
                    // Fail closed: an unset timer means we never observed a
                    // descent, so the rep is unverifiable and must not count.
                    val duration =
                        if (descentStartedAt == NOT_DESCENDING) 0L
                        else nowMillis - descentStartedAt

                    when {
                        duration < profile.minRepMillis ->
                            reject("Too fast — control the rep", nowMillis)

                        formBrokenThisRep ->
                            reject("Body wasn't straight — rep not counted", nowMillis)

                        else -> {
                            reps++
                            counted = true
                        }
                    }

                    phase = RepPhase.TOP
                    descentStartedAt = NOT_DESCENDING
                    formBrokenThisRep = false
                }
                coaching = if (!formOk) Coaching.STRAIGHTEN_BODY else Coaching.PUSH_UP
            }
        }

        return update(coaching, depth, formOk, counted, nowMillis)
    }

    private fun reject(message: String, now: Long) {
        rejectionMessage = message
        rejectionExpiresAt = now + REJECTION_HOLD_MILLIS
    }

    private fun update(
        coaching: Coaching,
        depth: Float,
        formOk: Boolean,
        counted: Boolean,
        now: Long,
    ) = CounterUpdate(
        reps = reps,
        phase = phase,
        coaching = coaching,
        depth = depth,
        formOk = formOk,
        repJustCounted = counted,
        rejection = rejectionMessage.takeIf { now < rejectionExpiresAt },
    )

    private companion object {
        const val NOT_DESCENDING = -1L
        const val REJECTION_HOLD_MILLIS = 1_800L
    }
}
