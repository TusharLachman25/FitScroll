package com.fitscroll.app.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The ledger as it is actually stored.
 *
 * BankMath already pins the rules down; what is left here is the part that
 * survives a reboot - serialising credits, reloading them, and refusing to take
 * the app down when what comes back is not what was written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankRepositoryTest {

    private lateinit var context: Context

    private fun prefs() = context.getSharedPreferences("fitscroll_bank", Context.MODE_PRIVATE)

    private fun freshBank(): BankRepository {
        BankRepository.resetForTests()
        return BankRepository.get(context)
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        BankRepository.resetForTests()
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `banked minutes survive being reloaded from disk`() {
        freshBank().earn(reps = 5, capMinutes = 1440)

        assertEquals(5 * 60, freshBank().balanceSeconds())
    }

    @Test
    fun `a partly spent credit reloads with what is left, not what it started with`() {
        val bank = freshBank()
        bank.earn(reps = 3, capMinutes = 1440)
        bank.spend(90)

        assertEquals(3 * 60 - 90, freshBank().balanceSeconds())
    }

    @Test
    fun `clearing the bank clears it on disk too`() {
        val bank = freshBank()
        bank.earn(reps = 2, capMinutes = 1440)
        bank.clear()

        assertEquals(0, freshBank().balanceSeconds())
    }

    @Test
    fun `an empty bank reports no expiry rather than a stale one`() {
        assertNull(freshBank().state.value.nextExpiryAt)

        val bank = freshBank()
        bank.earn(reps = 1, capMinutes = 1440)
        assertNotNull(bank.state.value.nextExpiryAt)
    }

    // -------------------------------------------------------------- corrupt

    @Test
    fun `a corrupt ledger loses the minutes rather than wedging the app`() {
        prefs().edit().putString("credits", "{not json at all").commit()

        // An unopenable app would be worse than a lost balance: the
        // accessibility service would keep the block on with no way to earn.
        assertEquals(0, freshBank().balanceSeconds())
    }

    @Test
    fun `a ledger entry missing its fields is discarded, not guessed at`() {
        prefs().edit().putString("credits", """[{"t":123}]""").commit()

        assertEquals(0, freshBank().balanceSeconds())
    }

    @Test
    fun `a bank recovered from corruption can be earned into again`() {
        prefs().edit().putString("credits", "]]garbage[[").commit()

        val bank = freshBank()
        bank.earn(reps = 4, capMinutes = 1440)

        assertEquals(4 * 60, freshBank().balanceSeconds())
    }

    // ---------------------------------------------------------- rep counters

    @Test
    fun `reps accumulate across sets within the day`() {
        val bank = freshBank()
        bank.earn(reps = 7, capMinutes = 1440)
        bank.earn(reps = 3, capMinutes = 1440)

        assertEquals(10, bank.state.value.repsToday)
        assertEquals(10, bank.state.value.repsAllTime)
    }

    @Test
    fun `the all-time count outlives the day count`() {
        freshBank().earn(reps = 6, capMinutes = 1440)

        // Yesterday's stamp, as if the app had been left overnight.
        prefs().edit().putString("today_date", "1999-01-01").commit()

        val today = freshBank()
        assertEquals(0, today.state.value.repsToday)
        assertEquals(6, today.state.value.repsAllTime)
    }

    @Test
    fun `a set after midnight starts the day count over without touching all-time`() {
        freshBank().earn(reps = 6, capMinutes = 1440)
        prefs().edit().putString("today_date", "1999-01-01").commit()

        val today = freshBank()
        today.earn(reps = 2, capMinutes = 1440)

        assertEquals(2, today.state.value.repsToday)
        assertEquals(8, today.state.value.repsAllTime)
    }

    // ------------------------------------------------------------------ cap

    @Test
    fun `earning past the cap banks the room left and reports the rest`() {
        val bank = freshBank()
        val outcome = bank.earn(reps = 20, capMinutes = 15)

        assertEquals(15 * 60, outcome.grantedSeconds)
        assertEquals(5 * 60, outcome.wastedSeconds)
        assertEquals(15 * 60, freshBank().balanceSeconds())
    }
}
