package com.fitscroll.app.block

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fitscroll.app.MainActivity
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.data.Settings
import com.fitscroll.app.data.SettingsRepository
import com.fitscroll.app.notify.FitScrollNotifications
import com.fitscroll.app.release.ReleaseGate

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
    private lateinit var keyguard: KeyguardManager
    private lateinit var classifier: ForegroundClassifier
    private lateinit var drainStore: DrainStore
    private lateinit var power: PowerManager
    private lateinit var notifications: FitScrollNotifications
    private lateinit var releaseGate: ReleaseGate

    private val handler = Handler(Looper.getMainLooper())

    /** The blocked package currently being charged for, if any. */
    private var drainingPackage: String? = null
    private var warningShown = false

    /** Cached so the readout can be updated without a PackageManager lookup. */
    private var drainAppLabel: String = ""
    private var lastDrainLabel: String? = null

    /** elapsedRealtime at the last charge, and the sub-second remainder of it. */
    private var lastChargedAt: Long = 0L
    private var carryMillis: Long = 0L
    private var lastPersistedAt: Long = 0L

    /**
     * The last package seen coming to the front, whether charged for or not.
     *
     * Kept because window-state events describe *transitions*, and the screen
     * going dark and coming back is not one. Without a remembered foreground,
     * the only route back into a drain is switching apps — which is exactly
     * what someone resuming an interrupted scroll does not do.
     *
     * Only ever written for a window that replaced the foreground app. A
     * keyboard or a share sheet used to land here too, so unlocking the phone
     * re-evaluated *that* package, found it unblocked, and left the meter off.
     */
    private var lastForegroundPackage: String? = null

    /**
     * Turning the screen off leaves the foreground app unchanged, so no window
     * event arrives and the drain would keep billing a phone in a pocket.
     *
     * Coming back has the mirror-image problem, and that one leaks screen time
     * rather than over-charging for it: unlocking straight back into the app
     * that was already in front raises no window-state change for it, and the
     * keyguard's own events are reported against `com.android.systemui`, which
     * is deliberately ignored below. The drain therefore stayed stopped and the
     * blocked app was free until the user happened to switch apps. The
     * remembered foreground is re-evaluated here instead.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopDrain()

                // SCREEN_ON arrives while the keyguard is still up, so resuming
                // is gated on the lock actually being open: charging someone
                // for glancing at their lock screen would be wrong.
                // USER_PRESENT covers the ordinary unlock, and the SCREEN_ON
                // path catches devices with no secure lock set, where
                // USER_PRESENT is not guaranteed to arrive at all.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> resumeIfUnlocked()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bank = BankRepository.get(this)
        settings = SettingsRepository.get(this)
        lockOverlay = LockOverlay(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        drainStore = DrainStore(this)
        notifications = FitScrollNotifications(this)
        releaseGate = ReleaseGate.get(this)
        classifier = ForegroundClassifier(this).also { it.start() }

        // Legal to omit the export flag today, because all three of these are
        // protected system broadcasts. Named anyway: adding a fourth action
        // that is not protected would throw here, and a SecurityException in
        // onServiceConnected takes the blocking down without a word.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        reconcileInterruptedDrain()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        onForegroundApp(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopDrain()
        classifier.stop()
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

    /**
     * Re-applies the rules to whatever was in front when the screen went dark.
     *
     * Routed through the same decision path as a window change on purpose, so
     * an empty bank raises the lock here too rather than merely leaving the
     * drain stopped: credits expire on a wall clock, so a balance that was
     * healthy at screen-off is not necessarily healthy on the way back.
     */
    private fun resumeIfUnlocked() {
        if (keyguard.isKeyguardLocked) return
        val foreground = lastForegroundPackage ?: return
        onForegroundApp(foreground)
    }

    private fun onForegroundApp(foreground: String) {
        val action = BlockPolicy.decide(
            foreground = foreground,
            ownPackage = packageName,
            blockedPackages = settings.current.blockedPackages,
            isTransientWindow = classifier.isTransientWindow(foreground),
            balanceSeconds = bank.balanceSeconds(),
            // Read per decision rather than latched at connect: a build can be
            // retired while the service is already running, and the next window
            // change should be the one that lets go.
            isRetired = releaseGate.state.value.retired,
        )

        // Returns before the remembered foreground is touched: a window that
        // only covered the app must leave no trace of having been in front.
        if (action is BlockAction.Ignore) return

        lastForegroundPackage = foreground

        when (action) {
            is BlockAction.Ignore -> Unit

            // Our own UI. The overlay is deliberately left alone: it is our
            // window too, and on some devices attaching it reports FitScroll as
            // foreground, which would tear the lock down the instant it
            // appeared. The buttons hide it explicitly when they are used.
            is BlockAction.StandDown -> stopDrain()

            // Swiping away to something else counts as backing off, so the lock
            // should not follow the user around on top of unrelated apps.
            is BlockAction.Release -> {
                lockOverlay.hide()
                stopDrain()
            }

            is BlockAction.Drain -> {
                lockOverlay.hide()
                startDrain(action.packageName, action.remainingSeconds)
            }

            is BlockAction.Lock -> {
                stopDrain()
                lock(action.packageName)
            }
        }
    }

    private fun startDrain(packageName: String, remainingSeconds: Int) {
        if (drainingPackage == packageName) return

        drainingPackage = packageName
        warningShown = false
        lastChargedAt = SystemClock.elapsedRealtime()
        lastPersistedAt = lastChargedAt
        carryMillis = 0L
        drainStore.record(packageName, System.currentTimeMillis())
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)

        val label = formatRemaining(remainingSeconds)
        drainAppLabel = AppInventory.labelFor(this, packageName)
        lastDrainLabel = label

        // Opening with the balance makes the cost visible at the moment of the
        // decision, which is the whole point of the app. The toast says it
        // once; the notification keeps saying it for the whole session, which
        // is the half a toast could never do.
        toast("$label left")
        notifications.showDraining(drainAppLabel, label)
    }

    private fun stopDrain() {
        drainingPackage = null
        warningShown = false
        carryMillis = 0L
        lastDrainLabel = null
        handler.removeCallbacks(tick)
        drainStore.clear()
        notifications.hideDraining()
    }

    /**
     * Settles a drain that was cut short by the process being killed.
     *
     * The service is never told it is about to go away, so the time between the
     * last charge and coming back is unobserved. It is charged for, because the
     * likeliest reason to be killed mid-drain is a memory-hungry app being
     * scrolled - but it is capped hard, since this is the one number a moved
     * clock or a long-dead process could otherwise inflate into an empty bank.
     */
    private fun reconcileInterruptedDrain() {
        val pending = drainStore.take() ?: return

        // Only while the phone is actually in use. Reconnecting after a kill
        // that happened with the screen off would otherwise bill for a pocket.
        if (keyguard.isKeyguardLocked || !power.isInteractive) return

        val owed = DrainMath.reconcileSeconds(
            pending = pending,
            nowWall = System.currentTimeMillis(),
            capSeconds = RECONCILE_CAP_SECONDS,
        )
        if (owed > 0) bank.spend(owed)

        // Assume the scroll is still going. The next window event corrects it
        // if not, and being wrong that way costs a moment of billing rather
        // than an unmetered app.
        lastForegroundPackage = pending.packageName
        onForegroundApp(pending.packageName)
    }

    /**
     * Charges for the screen time that has actually elapsed.
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

            val now = SystemClock.elapsedRealtime()

            // Measured rather than assumed. postDelayed re-arms only after the
            // body has run, so a tick is always a little longer than a second -
            // and a busy main thread makes it much longer. Charging a flat
            // second per tick undercharged every session by that difference,
            // and handed out real time whenever the phone stuttered.
            //
            // elapsedRealtime, not the wall clock: this is a duration, and it
            // must not move when a timezone or an NTP correction does.
            val elapsed = (now - lastChargedAt).coerceIn(0L, MAX_CATCHUP_MILLIS) + carryMillis
            lastChargedAt = now

            val seconds = (elapsed / 1_000L).toInt()
            // The sub-second remainder is carried, not dropped. Truncating it
            // every tick would give away most of a second per second.
            carryMillis = elapsed % 1_000L

            if (seconds > 0) {
                val spent = bank.spend(seconds)
                val remaining = bank.balanceSeconds()

                if (spent < seconds || remaining <= 0) {
                    stopDrain()
                    lock(packageName)
                    return
                }

                persistProgress(packageName, now)

                // Redrawn only when the number visibly changes: once a minute
                // for most of a session, once a second in the last minute,
                // which is exactly when it is worth watching.
                val label = formatRemaining(remaining)
                if (label != lastDrainLabel) {
                    lastDrainLabel = label
                    notifications.showDraining(drainAppLabel, label)
                }

                if (settings.current.warnBeforeLock &&
                    !warningShown &&
                    remaining <= Settings.WARN_AT_SECONDS
                ) {
                    warningShown = true
                    toast("${remaining}s left — bank more or wrap up")
                }
            }

            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    /**
     * Notes progress for the reconciler, on a timer rather than every tick.
     *
     * What it feeds is capped anyway, so accuracy to within the interval is
     * plenty, and the alternative is a second file rewrite every second on top
     * of the ledger's own.
     */
    private fun persistProgress(packageName: String, nowElapsed: Long) {
        if (nowElapsed - lastPersistedAt < PERSIST_EVERY_MILLIS) return
        lastPersistedAt = nowElapsed
        drainStore.record(packageName, System.currentTimeMillis())
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
            canLaunchCamera = BlockingStatus.canDrawOverlays(this),
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

        /** Most one tick may charge for, however long the main thread stalled. */
        const val MAX_CATCHUP_MILLIS = 5_000L

        /** How often the reconciler's marker is written while draining. */
        const val PERSIST_EVERY_MILLIS = 5_000L

        /** Ceiling on what an interrupted drain can cost when it is settled. */
        const val RECONCILE_CAP_SECONDS = 120
    }
}
