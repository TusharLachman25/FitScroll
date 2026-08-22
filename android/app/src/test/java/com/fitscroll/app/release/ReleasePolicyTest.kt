package com.fitscroll.app.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version floor, on its own.
 *
 * A floor rather than an on/off flag so that one number withdraws every build
 * below it at once, and so a notice cannot retire a build that did not exist
 * when it was written.
 */
class ReleasePolicyTest {

    @Test
    fun `a build below the floor is retired`() {
        assertTrue(ReleasePolicy.isRetired(minVersionCode = 6, installedVersionCode = 5))
        assertTrue(ReleasePolicy.isRetired(minVersionCode = 99, installedVersionCode = 1))
    }

    @Test
    fun `a build at the floor is supported`() {
        assertFalse(ReleasePolicy.isRetired(minVersionCode = 5, installedVersionCode = 5))
    }

    @Test
    fun `a build above the floor is supported`() {
        // The Play build will outrank every notice written for the sideloaded
        // ones, so publishing a floor cannot retire the release it points at.
        assertFalse(ReleasePolicy.isRetired(minVersionCode = 6, installedVersionCode = 7))
    }

    @Test
    fun `a floor of one retires nothing`() {
        // The published default, so the switch ships in the off position.
        assertFalse(ReleasePolicy.isRetired(minVersionCode = 1, installedVersionCode = 1))
        assertFalse(ReleasePolicy.isRetired(minVersionCode = 1, installedVersionCode = 5))
    }
}
