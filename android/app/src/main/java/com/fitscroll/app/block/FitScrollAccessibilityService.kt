package com.fitscroll.app.block

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.fitscroll.app.MainActivity
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.data.Settings
import com.fitscroll.app.data.SettingsRepository

/**
 * Watches which app is in front and enforces the bank against it.
 *
 * This runs on window-state changes rather than by polling, so the block lands
 * on the frame the app appears instead of up to a second later. It also does
 * the thing that actually matters: while a blocked app is on screen the balance
 * drains once a second, and the lock arrives mid-scroll the moment it empties —
 * not merely at the next launch.
 *
 * The service declares `canRetrieveWindowContent="false"`. Package names arrive
 * on the events themselves, so FitScroll never needs to read the contents of
 * any screen, and cannot.
 */
class FitScrollAccessibilityService : AccessibilityService() {

    private lateinit var bank: BankRepository
    private lateinit var settings: SettingsRepository
    private lateinit var lockOverlay: LockOverlay

    private val handler = Handler(Looper.getMainLooper())

    /** The blocked package currently being charged for, if any. */
    private var drainingPackage: String? = null
    private var warningShown = false

    /**
     * Turning the screen off leaves the foreground app unchanged, so no window
     * event arrives and the drain would keep billing a phone in a pocket.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopDrain()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bank = BankRepository.get(this)
        settings = SettingsRepository.get(this)
        lockOverlay = LockOverlay(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        // The notification shade and quick settings raise a window over the
        // current app without replacing it. Treating that as an app switch
        // would pause the drain every time the user glanced at a notification.
        if (packageName in TRANSIENT_SYSTEM_PACKAGES) return

        onForegroundApp(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopDrain()
        // Leaving a window behind when the service is switched off would cover
        // the whole phone with no way to reach the buttons.
        lockOverlay.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        lockOverlay.hide()
        super.onDestroy()
    }

    // ------------------------------------------------------------ decisions

    private fun onForegroundApp(foreground: String) {
        if (foreground == packageName) {
            // Our own UI. The overlay is deliberately left alone: it is our
            // window too, and on some devices attaching it reports FitScroll as
            // foreground, which would tear the lock down the instant it
            // appeared. The buttons hide it explicitly when they are used.
            stopDrain()
            return
        }

        if (foreground !in settings.current.blockedPackages) {
            // Swiping away to something else counts as backing off, so the lock
            // should not follow the user around on top of unrelated apps.
            lockOverlay.hide()
            stopDrain()
            return
        }

        val remaining = bank.balanceSeconds()
        if (remaining <= 0) {
            stopDrain()
            lock(foreground)
            return
        }

        lockOverlay.hide()
        startDrain(foreground, remaining)
    }

    private fun startDrain(packageName: String, remainingSeconds: Int) {
        if (drainingPackage == packageName) return

        drainingPackage = packageName
        warningShown = false
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)

        // Opening with the balance makes the cost visible at the moment of the
        // decision, which is the whole point of the app.
        toast(formatRemaining(remainingSeconds) + " left")
    }

    private fun stopDrain() {
        drainingPackage = null
        warningShown = false
        handler.removeCallbacks(tick)
    }

    /**
     * Charges one second per second of screen time.
     */
    private val tick = object : Runnable {
        override fun run() {
            val packageName = drainingPackage ?: return

            // Re-read settings each tick so un-blocking an app from Settings
            // takes effect immediately rather than at the next app switch.
            if (packageName !in settings.current.blockedPackages) {
                stopDrain()
                return
            }

            val spent = bank.spend(TICK_SECONDS)
            val remaining = bank.balanceSeconds()

            if (spent < TICK_SECONDS || remaining <= 0) {
                stopDrain()
                lock(packageName)
                return
            }

            if (settings.current.warnBeforeLock &&
                !warningShown &&
                remaining <= Settings.WARN_AT_SECONDS
            ) {
                warningShown = true
                toast("${remaining}s left — bank more or wrap up")
            }

            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    /**
     * Raises the lock screen directly over the blocked app.
     *
     * The lock deliberately lands on top of the app rather than bouncing the
     * user to the launcher first. Being ejected with no explanation reads as
     * the app glitching; a lock sitting over the feed reads as a lock. Leaving
     * on either button then performs the eject, so nothing is actually usable
     * underneath it.
     *
     * Guarded on the overlay already being up rather than on elapsed time. The
     * previous time-based debounce also suppressed a deliberate second attempt
     * a moment later, which let the blocked app straight through on the retry.
     */
    private fun lock(blockedPackage: String) {
        if (lockOverlay.isShowing) return

        lockOverlay.show(
            appLabel = AppInventory.labelFor(this, blockedPackage),
            balanceLabel = formatRemaining(bank.balanceSeconds()),
            onEarn = {
                leaveBlockedApp()
                openWorkout()
            },
            onDismiss = ::leaveBlockedApp,
        )

        if (!lockOverlay.isShowing) {
            // The window could not be attached. Fall back to ejecting, so a
            // failure here degrades to a weaker block rather than to none.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * Dismissing the lock must not drop the user back into the app it was
     * covering, or "Not now" would simply be a way through.
     */
    private fun leaveBlockedApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun openWorkout() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    .putExtra(MainActivity.EXTRA_START_WORKOUT, true),
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatRemaining(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 1) "${minutes}m" else "${seconds}s"
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val TICK_SECONDS = 1

        val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
        )
    }
}
