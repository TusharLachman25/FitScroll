package com.fitscroll.app.block

/**
 * The per-second accounting for one live drain.
 *
 * Pure, and driven by a clock the caller supplies, so every awkward case here
 * is exercisable without a device: a tick that arrives late, a main thread that
 * stalled, and the part-second left on the meter when the user walks away.
 *
 * **Which clock.** The caller must feed this [android.os.SystemClock.uptimeMillis],
 * not the wall clock and not `elapsedRealtime`:
 *
 *  - the wall clock moves when a timezone or an NTP correction does, and this
 *    is a duration;
 *  - `elapsedRealtime` keeps counting through deep sleep, so a phone that
 *    suspended with the meter still armed would wake up owing for the nap;
 *  - `uptimeMillis` advances exactly while the device is awake, which is the
 *    only time a screen could have been showing anything. It is also the clock
 *    `Handler.postDelayed` schedules on, so the tick and the meter never
 *    disagree about how long a second is.
 */
class DrainMeter(private val maxCatchUpMillis: Long = MAX_CATCH_UP_MILLIS) {

    private var lastChargedAt: Long = 0L
    private var carryMillis: Long = 0L
    private var running: Boolean = false

    val isRunning: Boolean get() = running

    fun start(nowAwake: Long) {
        lastChargedAt = nowAwake
        carryMillis = 0L
        running = true
    }

    /**
     * Whole seconds owed since the last charge.
     *
     * Measured rather than assumed. `postDelayed` re-arms only after the body
     * has run, so a tick is always a little longer than a second, and a busy
     * main thread makes it much longer. Charging a flat second per tick
     * undercharged every session by that difference.
     *
     * The sub-second remainder is carried rather than dropped — truncating it
     * every tick would give away most of a second per second — and the whole
     * gap is capped, because no single tick has any business charging for
     * minutes however long the thread was starved.
     */
    fun charge(nowAwake: Long): Int {
        if (!running) return 0

        val elapsed = (nowAwake - lastChargedAt).coerceIn(0L, maxCatchUpMillis) + carryMillis
        lastChargedAt = nowAwake

        val seconds = (elapsed / 1_000L).toInt()
        carryMillis = elapsed % 1_000L
        return seconds
    }

    /**
     * Charges what is left on the meter and stops it.
     *
     * This is the half that used to be thrown away. A drain that stopped
     * between ticks forgot both the part-tick that had not been charged yet and
     * the carried remainder underneath it — up to two seconds, forgiven on
     * every app switch, every screen-off and every lock. Somebody dipping into
     * Instagram thirty times a day was being handed a free minute for it.
     *
     * The last part-second is rounded rather than kept or dropped outright, so
     * the error is under half a second either way instead of always falling in
     * the user's favour.
     */
    fun settle(nowAwake: Long): Int {
        if (!running) return 0

        val owed = charge(nowAwake)
        val rounded = if (carryMillis >= ROUND_UP_AT_MILLIS) 1 else 0

        running = false
        carryMillis = 0L
        return owed + rounded
    }

    companion object {
        /**
         * Most one tick may charge for, however long the main thread stalled.
         *
         * Generous, because the clock underneath is uptime: it does not move
         * while the device is suspended, so a long gap here means the process
         * genuinely was starved with the screen on, and that time was real. The
         * cap is a backstop against the pathological case, not the mechanism
         * that keeps the meter honest.
         */
        const val MAX_CATCH_UP_MILLIS = 60_000L

        /** Half a second: the point where the leftover is worth a whole one. */
        const val ROUND_UP_AT_MILLIS = 500L
    }
}
