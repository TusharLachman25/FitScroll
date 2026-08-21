package com.fitscroll.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Everything the UI needs to render the bank, recomputed on each mutation. */
data class BankState(
    val balanceSeconds: Int = 0,
    /** Epoch millis at which the soonest credit dies, or null when empty. */
    val nextExpiryAt: Long? = null,
    /** Seconds that will evaporate unspent within the next hour. */
    val expiringWithinHourSeconds: Int = 0,
    val repsToday: Int = 0,
    val repsAllTime: Int = 0,
)

/**
 * Persists the credit ledger and exposes it as observable state.
 *
 * A process-wide singleton because the accessibility service, the lock screen
 * and the UI all mutate the same balance and run in the same process. Sharing
 * one in-memory ledger keeps them consistent; re-reading preferences on every
 * one-second drain tick would not.
 *
 * Writes go out on every mutation rather than being batched up. The drain
 * ticks once a second, and holding them would mean a process death mid-scroll
 * silently refunds screen time that was already spent. They are `apply()`
 * rather than `commit()`, so the guarantee is "handed to the write queue", not
 * "on disk" - a hard kill can still lose the most recent one. That is at most
 * the last second of a drain, which is not worth a synchronous disk write on
 * the main thread every second to avoid.
 */
class BankRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    private var credits: List<Credit> = loadCredits()

    private val _state = MutableStateFlow(computeState(System.currentTimeMillis()))
    val state: StateFlow<BankState> = _state.asStateFlow()

    // ---------------------------------------------------------------- reads

    fun balanceSeconds(): Int = synchronized(lock) {
        BankMath.balanceSeconds(credits, System.currentTimeMillis())
    }

    /**
     * Recomputes derived state without mutating the ledger.
     *
     * Credits expire on a wall clock, so the balance can drop with no action
     * taken. Screens that are visible tick this so the displayed number does
     * not go stale.
     */
    fun refresh() = synchronized(lock) {
        val now = System.currentTimeMillis()
        val live = BankMath.purge(credits, now)
        // Compared by value rather than by size. Purging also pulls a
        // future-stamped credit back to now, which leaves the count identical
        // while changing what is owed - a size check would have kept that fix
        // in memory and written the unclamped ledger back on the next mutation.
        if (live != credits) {
            credits = live
            persistCredits()
        }
        _state.value = computeState(now)
    }

    // --------------------------------------------------------------- writes

    /**
     * Banks completed push-ups. [capMinutes] is the user's configured ceiling.
     */
    fun earn(reps: Int, capMinutes: Int): EarnOutcome = synchronized(lock) {
        val now = System.currentTimeMillis()
        val outcome = BankMath.earn(credits, reps, now, capSeconds = capMinutes * 60)
        credits = outcome.credits
        persistCredits()
        if (reps > 0) recordReps(reps)
        _state.value = computeState(now)
        outcome
    }

    /**
     * Deducts screen time. Returns the seconds actually available, which is
     * less than [seconds] exactly when the bank has just run dry.
     */
    fun spend(seconds: Int): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        val outcome = BankMath.spend(credits, seconds, now)
        credits = outcome.credits
        persistCredits()
        _state.value = computeState(now)
        outcome.spentSeconds
    }

    /** Wipes the ledger. Exposed only through Settings, behind a confirmation. */
    fun clear() = synchronized(lock) {
        credits = emptyList()
        persistCredits()
        _state.value = computeState(System.currentTimeMillis())
    }

    // ----------------------------------------------------------- internals

    private fun computeState(now: Long): BankState {
        val live = BankMath.purge(credits, now)
        return BankState(
            balanceSeconds = live.sumOf { it.remainingSeconds },
            nextExpiryAt = BankMath.nextExpiryAt(live, now),
            expiringWithinHourSeconds = BankMath.expiringWithin(live, now, ONE_HOUR_MILLIS),
            repsToday = repsToday(),
            repsAllTime = prefs.getInt(KEY_REPS_ALL_TIME, 0),
        )
    }

    private fun recordReps(reps: Int) {
        val today = LocalDate.now().toString()
        val storedDay = prefs.getString(KEY_TODAY_DATE, null)
        val todayCount = if (storedDay == today) prefs.getInt(KEY_TODAY_REPS, 0) else 0
        prefs.edit()
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_REPS, todayCount + reps)
            .putInt(KEY_REPS_ALL_TIME, prefs.getInt(KEY_REPS_ALL_TIME, 0) + reps)
            .apply()
    }

    private fun repsToday(): Int =
        if (prefs.getString(KEY_TODAY_DATE, null) == LocalDate.now().toString()) {
            prefs.getInt(KEY_TODAY_REPS, 0)
        } else {
            0
        }

    private fun persistCredits() {
        val array = JSONArray()
        credits.forEach { credit ->
            array.put(
                JSONObject()
                    .put(FIELD_EARNED_AT, credit.earnedAt)
                    .put(FIELD_REMAINING, credit.remainingSeconds),
            )
        }
        prefs.edit().putString(KEY_CREDITS, array.toString()).apply()
    }

    private fun loadCredits(): List<Credit> {
        val raw = prefs.getString(KEY_CREDITS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                Credit(
                    earnedAt = entry.getLong(FIELD_EARNED_AT),
                    remainingSeconds = entry.getInt(FIELD_REMAINING),
                )
            }
        }.getOrElse {
            // A corrupt ledger must not wedge the app into a crash loop on every
            // launch. Losing banked minutes is recoverable; an unopenable app is
            // not, because the accessibility service would keep the block on.
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "fitscroll_bank"
        private const val KEY_CREDITS = "credits"
        private const val KEY_TODAY_DATE = "today_date"
        private const val KEY_TODAY_REPS = "today_reps"
        private const val KEY_REPS_ALL_TIME = "reps_all_time"
        private const val FIELD_EARNED_AT = "t"
        private const val FIELD_REMAINING = "s"

        private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L

        @Volatile
        private var instance: BankRepository? = null

        fun get(context: Context): BankRepository =
            instance ?: synchronized(this) {
                instance ?: BankRepository(context).also { instance = it }
            }
    }
}
