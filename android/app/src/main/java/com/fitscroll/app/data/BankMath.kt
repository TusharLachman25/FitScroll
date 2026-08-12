package com.fitscroll.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * One batch of earned screen time.
 *
 * A credit remembers *when* it was earned rather than when it expires, because
 * expiry is a fixed 24h offset and storing the origin keeps the "earned 3h ago"
 * copy on the home screen honest even after the credit is partly spent.
 */
data class Credit(
    /** Wall-clock time the reps were banked, in epoch millis. */
    val earnedAt: Long,
    /** Unspent seconds left in this batch. */
    val remainingSeconds: Int,
)

data class EarnOutcome(
    val credits: List<Credit>,
    /** Seconds actually banked. Lower than requested once the cap is hit. */
    val grantedSeconds: Int,
    /** Seconds thrown away because the bank was already at its cap. */
    val wastedSeconds: Int,
)

data class SpendOutcome(
    val credits: List<Credit>,
    /** Seconds actually deducted. Lower than requested when the bank ran dry. */
    val spentSeconds: Int,
)

/**
 * Pure bank arithmetic. Every function takes `now` explicitly and returns a new
 * list rather than mutating, so the rules are testable without a clock or a
 * device. [BankRepository] owns persistence and supplies the real time.
 *
 * The model: each push-up mints one minute that expires exactly 24h later.
 * Spending always drains the oldest live credit first, so minutes are consumed
 * in the order they would otherwise evaporate.
 */
object BankMath {

    /** A minute is worth 24h of shelf life, then it is gone. */
    const val EXPIRY_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** The exchange rate: one clean push-up buys one minute. */
    const val SECONDS_PER_REP: Int = 60

    /** Drops credits that are fully spent or past their 24h window. */
    fun purge(credits: List<Credit>, now: Long): List<Credit> =
        credits.filter { it.remainingSeconds > 0 && now - it.earnedAt < EXPIRY_MILLIS }

    /** Total spendable seconds right now. */
    fun balanceSeconds(credits: List<Credit>, now: Long): Int =
        purge(credits, now).sumOf { it.remainingSeconds }

    /**
     * Banks [reps] push-ups, clamped so the balance never exceeds [capSeconds].
     *
     * Overflow is reported rather than silently dropped: the workout screen
     * tells you when further reps stopped counting, which matters when someone
     * has set a small cap and is grinding out a set for nothing.
     */
    fun earn(credits: List<Credit>, reps: Int, now: Long, capSeconds: Int): EarnOutcome {
        val live = purge(credits, now)
        if (reps <= 0) return EarnOutcome(live, grantedSeconds = 0, wastedSeconds = 0)

        val wanted = reps * SECONDS_PER_REP
        val room = max(0, capSeconds - live.sumOf { it.remainingSeconds })
        val granted = min(wanted, room)

        val updated = if (granted > 0) live + Credit(earnedAt = now, remainingSeconds = granted) else live
        return EarnOutcome(
            credits = updated,
            grantedSeconds = granted,
            wastedSeconds = wanted - granted,
        )
    }

    /**
     * Deducts up to [seconds], oldest credit first.
     *
     * Returns how much was actually available; the caller uses a short-fall to
     * detect that the bank just hit zero and the lock screen is due.
     */
    fun spend(credits: List<Credit>, seconds: Int, now: Long): SpendOutcome {
        val live = purge(credits, now).sortedBy { it.earnedAt }
        if (seconds <= 0) return SpendOutcome(live, spentSeconds = 0)

        var outstanding = seconds
        val remaining = ArrayList<Credit>(live.size)

        for (credit in live) {
            if (outstanding <= 0) {
                remaining += credit
                continue
            }
            val taken = min(outstanding, credit.remainingSeconds)
            outstanding -= taken
            val left = credit.remainingSeconds - taken
            if (left > 0) remaining += credit.copy(remainingSeconds = left)
        }

        return SpendOutcome(credits = remaining, spentSeconds = seconds - outstanding)
    }

    /**
     * When the soonest-expiring live credit dies, or null if the bank is empty.
     */
    fun nextExpiryAt(credits: List<Credit>, now: Long): Long? =
        purge(credits, now).minOfOrNull { it.earnedAt + EXPIRY_MILLIS }

    /**
     * Seconds that will expire unspent within [windowMillis].
     *
     * Surfacing this is what stops the 24h rule feeling arbitrary: "12 min
     * expire within the hour" is a reason to open Instagram now, or a nudge
     * that yesterday's set went to waste.
     */
    fun expiringWithin(credits: List<Credit>, now: Long, windowMillis: Long): Int =
        purge(credits, now)
            .filter { it.earnedAt + EXPIRY_MILLIS - now <= windowMillis }
            .sumOf { it.remainingSeconds }
}
