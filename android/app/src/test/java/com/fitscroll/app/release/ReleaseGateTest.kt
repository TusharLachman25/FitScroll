package com.fitscroll.app.release

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The retirement check, including every way it can go wrong.
 *
 * The failure modes matter more than the happy path here. This is the only code
 * in FitScroll that talks to the network, and the network is the thing that is
 * always broken for somebody - so the rule under test throughout is that no
 * failure may ever be mistaken for a retirement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseGateTest {

    private lateinit var context: Context

    private val url = "https://example.invalid/status.json"

    /** Records what was asked for, and answers with whatever is queued. */
    private class FakeHost(var body: String?) {
        var calls = 0
        val fetch: suspend (String) -> String? = {
            calls++
            body
        }
    }

    /**
     * A gate over storage nothing else can reach.
     *
     * The name is fixed per test method rather than global: several of these
     * build a second gate over the *same* file on purpose, to stand in for a
     * cold start reading back what the first one wrote.
     */
    private fun gate(host: FakeHost, versionCode: Int = 5, statusUrl: String = url) =
        ReleaseGate(context, statusUrl, versionCode, host.fetch, prefsName = prefsName)

    private fun notice(minVersionCode: Int, message: String = "gone") =
        """{"minVersionCode":$minVersionCode,"message":"$message","updateUrl":"https://play.example/app"}"""

    /** Unique per test method, so no two share a cache. */
    private lateinit var prefsName: String

    @Rule
    @JvmField
    val testName = TestName()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Deliberately not the real file. The application object starts a live
        // check on process start, and clearing the shared one here only wins a
        // race it does not have to enter.
        prefsName = "fitscroll_release_test_${testName.methodName.hashCode()}"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ReleaseGate.resetForTests()
    }

    // ------------------------------------------------------------ the switch

    @Test
    fun `a notice above this build retires it`() = runBlocking {
        val gate = gate(FakeHost(notice(minVersionCode = 6)))
        gate.refresh()

        assertTrue(gate.state.value.retired)
        assertEquals("gone", gate.state.value.message)
        assertEquals("https://play.example/app", gate.state.value.updateUrl)
    }

    @Test
    fun `a notice at or below this build leaves it alone`() = runBlocking {
        val gate = gate(FakeHost(notice(minVersionCode = 5)))
        gate.refresh()

        assertFalse(gate.state.value.retired)
    }

    @Test
    fun `a build starts supported before anything has been checked`() {
        assertFalse(gate(FakeHost(null)).state.value.retired)
    }

    // ------------------------------------------------------------ fail open

    @Test
    fun `an unreachable notice leaves the build running`() = runBlocking {
        val gate = gate(FakeHost(null))
        gate.refresh()

        // Offline, captive portal, firewall, GitHub down. None of these are the
        // author withdrawing the build, and treating them as such would brick
        // the app on an aeroplane.
        assertFalse(gate.state.value.retired)
        assertEquals(0L, gate.state.value.checkedAt)
    }

    @Test
    fun `an unreadable notice leaves the build running`() = runBlocking {
        val gate = gate(FakeHost("<html>404 not found</html>"))
        gate.refresh()

        assertFalse(gate.state.value.retired)
    }

    @Test
    fun `a notice missing its version floor is ignored, not obeyed`() = runBlocking {
        val gate = gate(FakeHost("""{"message":"oops, forgot the number"}"""))
        gate.refresh()

        assertFalse(gate.state.value.retired)
    }

    @Test
    fun `a retirement is not undone by the network going away afterwards`() = runBlocking {
        val host = FakeHost(notice(minVersionCode = 6))
        val gate = gate(host)
        gate.refresh()
        assertTrue(gate.state.value.retired)

        host.body = null
        gate.refresh(force = true)

        assertTrue(gate.state.value.retired)
    }

    @Test
    fun `a build with no notice url never reaches the network at all`() = runBlocking {
        val host = FakeHost(notice(minVersionCode = 99))
        val gate = gate(host, statusUrl = "")
        gate.refresh(force = true)

        // How the Play build is compiled: nothing to call, so it cannot.
        assertEquals(0, host.calls)
        assertFalse(gate.state.value.retired)
    }

    // -------------------------------------------------------------- caching

    @Test
    fun `a retirement survives the app being restarted`() = runBlocking {
        gate(FakeHost(notice(minVersionCode = 6))).refresh()

        // A second gate over the same preferences is what a cold start looks
        // like. It must know without asking, or a withdrawn build comes back to
        // life every time it is opened offline.
        val restarted = gate(FakeHost(null))
        assertTrue(restarted.state.value.retired)
        assertEquals("gone", restarted.state.value.message)
    }

    @Test
    fun `checks are rate limited, and force overrides that`() = runBlocking {
        val host = FakeHost(notice(minVersionCode = 1))
        val gate = gate(host)

        gate.refresh()
        gate.refresh()
        gate.refresh()
        assertEquals(1, host.calls)

        gate.refresh(force = true)
        assertEquals(2, host.calls)
    }

    @Test
    fun `a retirement can be lifted by a later notice`() = runBlocking {
        val host = FakeHost(notice(minVersionCode = 6))
        val gate = gate(host)
        gate.refresh()
        assertTrue(gate.state.value.retired)

        // Flipped by mistake, or a launch that slipped. Nothing about this is
        // one-way.
        host.body = notice(minVersionCode = 1, message = "")
        gate.refresh(force = true)

        assertFalse(gate.state.value.retired)
        assertNull(gate.state.value.message)
    }
}
