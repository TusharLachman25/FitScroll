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
            // Our own UI, most likely the workout screen the lock just sent
            // them to. Nothing to charge and nothing to cover.
            lockOverlay.hide()
            stopDrain()
            return
        }

        if (foreground !in settings.current.blockedPackages) {
            stopDrain()
            // The overlay is deliberately not dismissed here. Locking ejects to
            // the launcher first, which arrives as exactly this case, and
            // hiding on it would tear the lock down the instant it appeared.
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
     * Ejects from the blocked app and raises the lock screen over the launcher.
     *
     * Guarded on the overlay already being up rather than on elapsed time. The
     * previous time-based debounce also suppressed a deliberate second attempt
     * a moment later, which let the blocked app straight through on the retry.
     */
    private fun lock(blockedPackage: String) {
        if (lockOverlay.isShowing) return

        // Home first, so the lock covers the launcher rather than a feed that
        // is still running, and still playing audio, underneath it.
        performGlobalAction(GLOBAL_ACTION_HOME)

        lockOverlay.show(
            appLabel = AppInventory.labelFor(this, blockedPackage),
            balanceLabel = formatRemaining(bank.balanceSeconds()),
            onEarn = ::openWorkout,
            onDismiss = { },
        )
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
