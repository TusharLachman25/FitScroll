package com.fitscroll.app

import android.app.Application
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.notify.ExpiryReminder
import com.fitscroll.app.notify.FitScrollNotifications
import com.fitscroll.app.release.ReleaseGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the two things that outlive every screen: the notification channels and
 * the expiry reminder.
 *
 * The reminder is armed from the ledger here rather than from a screen because
 * the process is normally alive for the accessibility service long after the
 * last screen has gone - and because a reminder that only worked while the app
 * was open would be pointless.
 */
class FitScrollApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        FitScrollNotifications(this).apply {
            ensureChannels()
            // The drain readout is ongoing, so nothing takes it down but the
            // drain stopping - and a killed process never gets to say so. It
            // sat there afterwards claiming a session that had died with the
            // process, un-dismissable below Android 14. Any process start means
            // no drain is running yet, so this is always the truth.
            hideDraining()
        }

        // Checked once per process start, rate-limited inside the gate. Failing
        // to reach it leaves the previous answer standing, so this can be
        // fire-and-forget: there is no failure here worth reporting to anyone.
        scope.launch { ReleaseGate.get(this@FitScrollApp).refresh() }

        // Distinct on the expiry instant alone. The balance moves every second
        // during a drain and none of that changes when the oldest batch dies,
        // so collecting the whole state would re-arm the alarm once a second.
        scope.launch {
            BankRepository.get(this@FitScrollApp).state
                .map { it.nextExpiryAt }
                .distinctUntilChanged()
                .collect { ExpiryReminder.schedule(this@FitScrollApp, it) }
        }
    }
}
