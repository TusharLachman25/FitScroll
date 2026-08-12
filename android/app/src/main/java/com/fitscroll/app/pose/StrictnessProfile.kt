package com.fitscroll.app.pose

/**
 * How exacting the rep counter is, on the 1-5 scale exposed in Settings.
 *
 * Every level tightens five things at once, because loosening only depth (say)
 * produces a counter that rewards a fast, sloppy half-rep just as much as a
 * slow clean one:
 *
 *  - [downElbowAngle]   how far you must bend at the bottom
 *  - [upElbowAngle]     how far you must extend at the top
 *  - [minBodyLineAngle] how much hip sag or pike is tolerated
 *  - [formGraceMillis]  how long you may be outside that tolerance anyway
 *  - [minRepMillis]     the floor on rep duration, which is what stops someone
 *                       waving an arm past the camera and banking a week of
 *                       screen time in thirty seconds
 *
 * Angles are degrees. The elbow angle is shoulder-elbow-wrist, so a straight
 * arm is ~180 and a deep push-up bottoms out near 70-90. The body line is
 * shoulder-hip-knee, where 180 is a perfect plank and both sagging and piking
 * pull it down.
 *
 * The body-line numbers are deliberately well under a true plank. This is a 2D
 * estimate from one camera: unless the lens sits exactly perpendicular to you,
 * perspective foreshortens the torso and a genuinely straight back measures far
 * below 180. Thresholds tight enough to be "correct" in geometry reject real
 * push-ups on real phone placements.
 */
data class StrictnessProfile(
    val level: Int,
    val label: String,
    val blurb: String,
    val downElbowAngle: Float,
    val upElbowAngle: Float,
    val minBodyLineAngle: Float,
    /**
     * How much time within a single rep may be spent outside the body-line
     * tolerance before the rep is voided.
     *
     * Non-zero on every level on purpose. ML Kit's hip landmark jitters by
     * several degrees on a motionless subject, so judging form frame by frame
     * throws away clean reps over one noisy sample. Real sag lasts; noise does
     * not.
     */
    val formGraceMillis: Long,
    val minRepMillis: Long,
    val minConfidence: Float,
) {
    companion object {

        private val PROFILES = listOf(
            StrictnessProfile(
                level = 1,
                label = "Casual",
                blurb = "Counts almost any up-and-down. Good for warming up or if your camera angle is awkward.",
                downElbowAngle = 115f,
                upElbowAngle = 145f,
                minBodyLineAngle = 100f,
                formGraceMillis = 1_500L,
                minRepMillis = 350L,
                minConfidence = 0.30f,
            ),
            StrictnessProfile(
                level = 2,
                label = "Relaxed",
                blurb = "Forgiving on depth, still expects a recognisable push-up.",
                downElbowAngle = 105f,
                upElbowAngle = 150f,
                minBodyLineAngle = 118f,
                formGraceMillis = 1_100L,
                minRepMillis = 450L,
                minConfidence = 0.40f,
            ),
            StrictnessProfile(
                level = 3,
                label = "Standard",
                blurb = "Roughly a gym-legal push-up: bend past 90 degrees, lock out at the top, keep a straight back.",
                downElbowAngle = 90f,
                upElbowAngle = 156f,
                minBodyLineAngle = 132f,
                formGraceMillis = 800L,
                minRepMillis = 600L,
                minConfidence = 0.50f,
            ),
            StrictnessProfile(
                level = 4,
                label = "Strict",
                blurb = "Chest low, full lockout, no hip sag. Expect your count to drop.",
                downElbowAngle = 80f,
                upElbowAngle = 162f,
                minBodyLineAngle = 144f,
                formGraceMillis = 500L,
                minRepMillis = 750L,
                minConfidence = 0.58f,
            ),
            StrictnessProfile(
                level = 5,
                label = "Brutal",
                blurb = "Near-floor depth, dead-straight body, no bouncing. Every minute is earned.",
                downElbowAngle = 72f,
                upElbowAngle = 168f,
                minBodyLineAngle = 155f,
                formGraceMillis = 300L,
                minRepMillis = 900L,
                minConfidence = 0.65f,
            ),
        )

        fun forLevel(level: Int): StrictnessProfile = PROFILES[level.coerceIn(1, 5) - 1]

        fun all(): List<StrictnessProfile> = PROFILES
    }
}
