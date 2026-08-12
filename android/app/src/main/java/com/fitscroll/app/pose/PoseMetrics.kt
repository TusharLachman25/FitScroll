package com.fitscroll.app.pose

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * The three numbers the rep counter actually reasons about, distilled from a
 * full skeleton so the counting logic stays free of ML Kit types and testable
 * on the JVM.
 */
data class PoseMetrics(
    /** Shoulder-elbow-wrist, in degrees. ~180 is a locked-out arm. */
    val elbowAngle: Float,
    /** Shoulder-hip-knee, in degrees. 180 is a flat plank. */
    val bodyLineAngle: Float,
    /** Lowest landmark likelihood among the joints used, 0..1. */
    val confidence: Float,
)

object Geometry {

    /**
     * Interior angle at [b] formed by the points a-b-c, in degrees (0..180).
     */
    fun angle(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float,
    ): Float {
        val abx = ax - bx
        val aby = ay - by
        val cbx = cx - bx
        val cby = cy - by

        val magAb = hypot(abx, aby)
        val magCb = hypot(cbx, cby)
        if (magAb < EPSILON || magCb < EPSILON) return 180f

        val cosine = ((abx * cbx + aby * cby) / (magAb * magCb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /**
     * Smooths a noisy per-frame angle.
     *
     * ML Kit jitters by a few degrees frame to frame even on a still subject.
     * Unsmoothed, that jitter straddles a threshold and machine-guns phantom
     * reps; [factor] near 0.35 removes it without adding visible lag.
     */
    fun smooth(previous: Float?, current: Float, factor: Float = 0.35f): Float =
        if (previous == null || abs(previous - current) > RESET_JUMP_DEGREES) {
            // A large jump means the subject moved or was re-acquired, not
            // noise. Snapping avoids a slow drift across the whole rep.
            current
        } else {
            previous + (current - previous) * factor
        }

    private const val EPSILON = 1e-4f
    private const val RESET_JUMP_DEGREES = 45f
}
