package com.fitscroll.app.block

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The blocking decision, including the cases that used to hand out free screen
 * time: a window that appeared over a blocked app without replacing it.
 */
class BlockPolicyTest {

    private val instagram = "com.instagram.android"
    private val keyboard = "com.google.android.inputmethod.latin"
    private val own = "com.fitscroll.app"
    private val blocked = setOf(instagram)

    private fun decide(
        foreground: String,
        transient: Boolean = false,
        balance: Int = 600,
        retired: Boolean = false,
    ) = BlockPolicy.decide(
        foreground = foreground,
        ownPackage = own,
        blockedPackages = blocked,
        isTransientWindow = transient,
        balanceSeconds = balance,
        isRetired = retired,
    )

    @Test
    fun `a blocked app with credit starts draining`() {
        assertEquals(BlockAction.Drain(instagram, 600), decide(instagram))
    }

    @Test
    fun `a blocked app with an empty bank locks`() {
        assertEquals(BlockAction.Lock(instagram), decide(instagram, balance = 0))
    }

    @Test
    fun `a bank that has gone negative still locks`() {
        assertEquals(BlockAction.Lock(instagram), decide(instagram, balance = -5))
    }

    @Test
    fun `an unrelated app releases the lock and stops the meter`() {
        assertEquals(BlockAction.Release, decide("com.android.chrome"))
    }

    @Test
    fun `our own ui stops the meter without dropping the lock window`() {
        assertEquals(BlockAction.StandDown, decide(own))
    }

    @Test
    fun `the keyboard opening over a blocked app changes nothing`() {
        assertEquals(BlockAction.Ignore, decide(keyboard, transient = true))
    }

    @Test
    fun `a share sheet over a blocked app changes nothing`() {
        assertEquals(BlockAction.Ignore, decide("com.android.intentresolver", transient = true))
    }

    @Test
    fun `a transient window does not release the lock either`() {
        // The lock is up, the bank is empty, and a system dialog appears over
        // it. Hiding the lock here would uncover the blocked app.
        assertEquals(BlockAction.Ignore, decide("com.android.systemui", transient = true, balance = 0))
    }

    @Test
    fun `a blocked app is never treated as transient`() {
        // Fail loudly rather than silently: a misclassification here is
        // unmetered screen time, which is the one outcome that must not happen.
        assertEquals(BlockAction.Drain(instagram, 600), decide(instagram, transient = true))
        assertEquals(BlockAction.Lock(instagram), decide(instagram, transient = true, balance = 0))
    }

    @Test
    fun `blocking nothing leaves every app alone`() {
        val action = BlockPolicy.decide(
            foreground = instagram,
            ownPackage = own,
            blockedPackages = emptySet(),
            isTransientWindow = false,
            balanceSeconds = 0,
        )
        assertEquals(BlockAction.Release, action)
    }

    // ------------------------------------------------------------ retirement

    @Test
    fun `a retired build stops blocking, even with an empty bank`() {
        // The failure that matters: retiring a build that kept enforcing would
        // strand its users behind a lock screen with no way to earn out of it.
        assertEquals(BlockAction.Release, decide(instagram, balance = 0, retired = true))
    }

    @Test
    fun `a retired build stops draining a bank that still has minutes in it`() {
        assertEquals(BlockAction.Release, decide(instagram, balance = 600, retired = true))
    }

    @Test
    fun `retirement outranks the transient-window test`() {
        // Release rather than Ignore, so a lock already on screen is dropped
        // rather than frozen in place by the next keyboard that appears.
        assertEquals(BlockAction.Release, decide(keyboard, transient = true, retired = true))
    }

    @Test
    fun `an unretired build is unaffected by the flag being present`() {
        assertEquals(BlockAction.Drain(instagram, 600), decide(instagram, retired = false))
    }
}
