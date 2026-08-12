package com.fitscroll.app.pose

/**
 * How exacting the rep counter is, on the 1-5 scale exposed in Settings.
 *
 * Every level tightens four independent things at once, because loosening only
 * depth (say) produces a counter that rewards a fast, sloppy half-rep just as
 * much as a slow clean one:
 *
 *  - [downElbowAngle]   how far you must bend at the bottom
 *  - [upElbowAngle]     how far you must extend at the top
 *  - [minBodyLineAngle] how much hip sag or pike is tolerated
 *  - [minRepMillis]     the floor on rep duration, which is what stops someone
 *                       waving an arm past the camera and banking a week of
 *                       screen time in thirty seconds
 *
 * Angles are degrees. The elbow angle is shoulder-elbow-wrist, so a straight
 * arm is ~180 and a deep push-up bottoms out near 70-90. The body line is
 * shoulder-hip-knee, where 180 is a perfect plank and both sagging and piking
 * pull it down.
 */
data class StrictnessProfile(
    val level: Int,
    val label: String,
    val blurb: String,
    val downElbowAngle: Float,
    val upElbowAngle: Float,
    val minBodyLineAngle: Float,
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
                minBodyLineAngle = 115f,
                minRepMillis = 350L,
                minConfidence = 0.30f,
            ),
            StrictnessProfile(
                level = 2,
                label = "Relaxed",
                blurb = "Forgiving on depth, still expects a recognisable push-up.",
                downElbowAngle = 105f,
                upElbowAngle = 150f,
                minBodyLineAngle = 138f,
                minRepMillis = 450L,
                minConfidence = 0.40f,
            ),
            StrictnessProfile(
                level = 3,
                label = "Standard",
                blurb = "Roughly a gym-legal push-up: bend past 90 degrees, lock out at the top, keep a straight back.",
                downElbowAngle = 90f,
                upElbowAngle = 156f,
                minBodyLineAngle = 150f,
                minRepMillis = 600L,
                minConfidence = 0.50f,
            ),
            StrictnessProfile(
                level = 4,
                label = "Strict",
                blurb = "Chest low, full lockout, no hip sag. Expect your count to drop.",
                downElbowAngle = 80f,
                upElbowAngle = 162f,
                minBodyLineAngle = 158f,
                minRepMillis = 750L,
                minConfidence = 0.60f,
            ),
            StrictnessProfile(
                level = 5,
                label = "Brutal",
                blurb = "Near-floor depth, dead-straight body, no bouncing. Every minute is earned.",
                downElbowAngle = 72f,
                upElbowAngle = 168f,
                minBodyLineAngle = 165f,
                minRepMillis = 900L,
                minConfidence = 0.68f,
            ),
        )

        fun forLevel(level: Int): StrictnessProfile = PROFILES[level.coerceIn(1, 5) - 1]

        fun all(): List<StrictnessProfile> = PROFILES
    }
}
