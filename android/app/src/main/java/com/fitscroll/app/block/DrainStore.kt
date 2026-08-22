package com.fitscroll.app.block

import android.content.Context

/** A drain that was still running when the process went away. */
data class PendingDrain(
    val packageName: String,
    /** Wall-clock millis at which this drain was last charged. */
    val lastChargedAtWall: Long,
)

/**
 * Reconciliation arithmetic, kept pure so the awkward cases are testable.
 *
 * The service is not told when it is killed, so a drain can be interrupted
 * halfway through a scroll. Everything between the last charge and the service
 * coming back is unobserved time: the user may well have kept scrolling, or may
 * have put the phone down.
 */
object DrainMath {

    /**
     * Seconds to charge for the gap between [pending] and now.
     *
     * Capped rather than trusted. The gap is measured on the wall clock — the
     * only clock that survives a reboot — so it is exactly the number a moved
     * clock could inflate, and an uncapped value would empty a bank that took
     * real push-ups to fill. A negative gap means the clock went backwards and
     * is worth nothing.
     */
    fun reconcileSeconds(pending: PendingDrain, nowWall: Long, capSeconds: Int): Int {
        val gapMillis = nowWall - pending.lastChargedAtWall
        if (gapMillis <= 0L) return 0
        return (gapMillis / 1000L).coerceAtMost(capSeconds.toLong()).toInt()
    }
}

/**
 * The two things about the current session that have to outlive the process.
 *
 * **The pending drain** is which app was being charged for, so an interrupted
 * one is not simply forgotten. Written on a timer rather than on every tick:
 * the record only has to be accurate to within the interval, because what it
 * feeds is capped anyway, and the alternative is a second file rewrite every
 * single second on top of the ledger's.
 *
 * **The remembered foreground** is whatever last took the screen, drain or no
 * drain. It used to live only in memory, which defeated the one job it has.
 * Window-state events describe *transitions*, so unlocking straight back into
 * the app that was already in front raises no event for it and the meter has
 * nothing to react to — the remembered package is the only way back into a
 * drain. Process death is both common and exactly when that recovery is needed,
 * and an in-memory field is gone by then: scroll Instagram, lock the phone, let
 * Android reclaim the process overnight, and the morning's scrolling was free
 * until the user happened to switch apps. It survives on disk now.
 */
class DrainStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------- pending drain

    fun record(packageName: String, lastChargedAtWall: Long) {
        prefs.edit()
            .putString(KEY_PACKAGE, packageName)
            .putLong(KEY_LAST_CHARGED, lastChargedAtWall)
            .apply()
    }

    /** Reads the pending drain and clears it, so it can only be settled once. */
    fun take(): PendingDrain? {
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        val lastCharged = prefs.getLong(KEY_LAST_CHARGED, 0L)
        clear()
        return if (lastCharged <= 0L) null else PendingDrain(packageName, lastCharged)
    }

    fun clear() {
        prefs.edit().remove(KEY_PACKAGE).remove(KEY_LAST_CHARGED).apply()
    }

    // -------------------------------------------------- remembered foreground

    /**
     * Notes what is in front now.
     *
     * Deliberately *not* cleared when a drain stops. A drain stopping is the
     * ordinary case — the screen went off — and that is precisely the moment
     * the remembered package becomes load-bearing, because the way back is an
     * unlock rather than an app switch. Only a different app taking the screen
     * replaces it.
     */
    fun rememberForeground(packageName: String) {
        prefs.edit().putString(KEY_FOREGROUND, packageName).apply()
    }

    fun lastForeground(): String? = prefs.getString(KEY_FOREGROUND, null)

    private companion object {
        const val PREFS_NAME = "fitscroll_drain"
        const val KEY_PACKAGE = "package"
        const val KEY_LAST_CHARGED = "last_charged_at"
        const val KEY_FOREGROUND = "last_foreground"
    }
}
