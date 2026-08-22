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
import com.fitscroll.app.ui.formatBalance

/**
 * Watches which app is in front and enforces the bank against it.
 *
 * This runs on accessibility events rather than by polling, so the block lands
 * on the frame the app appears instead of up to a second later. It also does
 * the thing that actually matters: while a blocked app is on screen the balance
 * drains once a second, and the lock arrives mid-scroll the moment it empties —
 * not merely at the next launch.
 *
 * Two kinds of event feed it, and they mean different things:
 *
 *  - a **window-state change** says a window just took the screen, which is the
 *    only signal that an app switch happened at all;
 *  - a **scroll** says a package is on screen *right now*.
 *
 * The second exists because the first only describes transitions. Anything that
 * covers a blocked app without replacing it — the notification shade above all
 * — leaves the app underneath untouched, so when it goes away there is no
 * window event to react to. Before scrolls were listened for, a meter stopped
 * by any such window stayed stopped until the user happened to switch apps.
 * Scrolling the feed now re-arms it within one flick.
 *
 * The service declares `canRetrieveWindowContent="false"` and reads nothing
 * from an event but its package name and window class. It cannot see the
 * contents of any screen, including the ones it counts scrolls on.
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
    private val meter = DrainMeter()

    /**
     * Guards against being connected twice on one instance.
     *
     * Setting up again would register a second screen receiver and strand the
     * previous classifier's receiver, content observers and coroutine scope
     * with nothing left holding a reference to stop them.
     */
    private var connected = false

    /** The blocked package currently being charged for, if any. */
    private var drainingPackage: String? = null
    private var warningShown = false

    /** Cached so the readout can be updated without a PackageManager lookup. */
    private var drainAppLabel: String = ""
    private var lastDrainLabel: String? = null

    private var lastPersistedAt: Long = 0L

    /**
     * When the current drain started on a *guess* rather than on an event, or
     * [NOT_GUESSED] once something has confirmed it.
     *
     * Resuming after an unlock or a process kill means acting on a remembered
     * package, and a remembered package is not evidence: Android may well have
     * killed the app while the screen was off and dropped the user on their
     * home screen. Billing that guess used to be open-ended, because the thing
     * meant to correct it — the next window event — never arrives if the screen
     * the user is actually looking at raised its window before we asked. A
     * guessed drain now expires on its own unless an event vouches for it, so
     * being wrong costs [GUESS_GRACE_MILLIS] rather than the whole bank.
     */
    private var guessedAt: Long = NOT_GUESSED

    /**
     * The last package seen coming to the front, whether charged for or not.
     *
     * Kept because window-state events describe *transitions*, and the screen
     * going dark and coming back is not one. Without a remembered foreground,
     * the only route back into a drain is switching apps — which is exactly
     * what someone resuming an interrupted scroll does not do.
     *
     * Mirrored to [DrainStore] rather than held only here, because the case it
     * exists for is the one that takes the process with it.
     *
     * Only ever written for a window that replaced the foreground app. A
     * keyboard or a share sheet used to land here too, so unlocking the phone
     * re-evaluated *that* package, found it unblocked, and left the meter off.
     */
    private var lastForegroundPackage: String? = null

    /** Throttling for scroll events, which arrive far faster than once a tick. */
    private var lastHeartbeatPackage: String? = null
    private var lastHeartbeatAt: Long = 0L

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
        if (connected) return
        connected = true

        bank = BankRepository.get(this)
        settings = SettingsRepository.get(this)
        lockOverlay = LockOverlay(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        drainStore = DrainStore(this)
        notifications = FitScrollNotifications(this)
        releaseGate = ReleaseGate.get(this)
        classifier = ForegroundClassifier(this).also { it.start() }

        lastForegroundPackage = drainStore.lastForeground()

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
        val packageName = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                onForegroundApp(packageName, event.className?.toString(), confirmed = true)

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrollHeartbeat(packageName)

            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Guarded as a whole: everything in here is set up in onServiceConnected,
        // and an unbind that arrives without one would take a lateinit with it.
        if (connected) {
            connected = false
            stopDrain()
            classifier.stop()
            runCatching { unregisterReceiver(screenReceiver) }
            // Leaving a window behind when the service is switched off would
            // cover the whole phone with no way to reach the buttons.
            lockOverlay.hide()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::lockOverlay.isInitialized) lockOverlay.hide()
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
     *
     * Unconfirmed, though — see [guessedAt]. What is remembered is where the
     * phone *was*, and an unlock is not proof it is still there.
     */
    private fun resumeIfUnlocked() {
        if (keyguard.isKeyguardLocked) return
        val foreground = lastForegroundPackage ?: return
        onForegroundApp(foreground, className = null, confirmed = false)
    }

    /**
     * A package saying it is on screen right now.
     *
     * Throttled, because scrolling produces these many times a second and each
     * one would otherwise re-purge the ledger to read a balance that has not
     * moved. A package that differs from the last one is always acted on
     * immediately: that is a real change and worth a tick's latency to nobody.
     */
    private fun onScrollHeartbeat(packageName: String) {
        val now = SystemClock.uptimeMillis()
        if (packageName == lastHeartbeatPackage &&
            now - lastHeartbeatAt < HEARTBEAT_MIN_INTERVAL_MILLIS
        ) {
            return
        }
        lastHeartbeatPackage = packageName
        lastHeartbeatAt = now

        // No window class is passed: a scroll carries none, and it is not
        // claiming a window just arrived. It is evidence of presence, which is
        // exactly what a resumed guess needs and what the shade destroyed.
        onForegroundApp(packageName, className = null, confirmed = true)
    }

    private fun onForegroundApp(foreground: String, className: String?, confirmed: Boolean) {
        // The activity test guards a session in progress and nothing else, so
        // the window class is only offered while there is one to guard.
        val enforcing = drainingPackage != null || lockOverlay.isShowing

        val action = BlockPolicy.decide(
            foreground = foreground,
            ownPackage = packageName,
            blockedPackages = settings.current.blockedPackages,
            isTransientWindow = classifier.isTransientWindow(
                packageName = foreground,
                className = className.takeIf { enforcing },
            ),
            balanceSeconds = bank.balanceSeconds(),
            // Read per decision rather than latched at connect: a build can be
            // retired while the service is already running, and the next window
            // change should be the one that lets go.
            isRetired = releaseGate.state.value.retired,
        )

        // Returns before the remembered foreground is touched: a window that
        // only covered the app must leave no trace of having been in front.
        if (action is BlockAction.Ignore) return

        rememberForeground(foreground)

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
                startDrain(action.packageName, action.remainingSeconds, confirmed)
            }

            // Locked on a guess as readily as on an event, unlike the drain
            // above. The two failures are not comparable: a drain that guessed
            // wrong spends a bank silently, which is why it is put on a clock,
            // whereas a lock that guessed wrong is a screen the user is looking
            // at with a button on it, and one tap sends it away. Waiting for
            // confirmation here would mean an empty bank, an unlock straight
            // back into a paused video, and nothing to stop it playing — the
            // whole app defeated by not scrolling.
            is BlockAction.Lock -> {
                stopDrain()
                lock(action.packageName)
            }
        }
    }

    private fun rememberForeground(foreground: String) {
        if (foreground == lastForegroundPackage) return
        lastForegroundPackage = foreground
        drainStore.rememberForeground(foreground)
    }

    private fun startDrain(packageName: String, remainingSeconds: Int, confirmed: Boolean) {
        if (drainingPackage == packageName) {
            // Already running. All this event can add is vouching for it.
            if (confirmed) guessedAt = NOT_GUESSED
            return
        }

        val now = SystemClock.uptimeMillis()

        drainingPackage = packageName
        guessedAt = if (confirmed) NOT_GUESSED else now
        warningShown = false
        meter.start(now)
        lastPersistedAt = now
        drainStore.record(packageName, System.currentTimeMillis())
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)

        val label = formatBalance(remainingSeconds)
        drainAppLabel = AppInventory.labelFor(this, packageName)
        lastDrainLabel = label

        // Opening with the balance makes the cost visible at the moment of the
        // decision, which is the whole point of the app. The toast says it
        // once; the notification keeps saying it for the whole session, which
        // is the half a toast could never do.
        toast("$label left")
        notifications.showDraining(drainAppLabel, label)
    }

    /**
     * Stops the meter, charging what is still on it.
     *
     * The settle is the point. A drain used to end by simply forgetting where
     * it was, which handed back the part-tick that had not been charged yet
     * plus the carried remainder underneath it — up to two seconds, every app
     * switch, every screen-off, every lock.
     */
    private fun stopDrain() {
        if (drainingPackage != null) {
            val owed = meter.settle(SystemClock.uptimeMillis())
            if (owed > 0) bank.spend(owed)
        }

        drainingPackage = null
        guessedAt = NOT_GUESSED
        warningShown = false
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
        val pending = drainStore.take()

        // Only while the phone is actually in use. Reconnecting after a kill
        // that happened with the screen off would otherwise bill for a pocket.
        val awake = !keyguard.isKeyguardLocked && power.isInteractive

        // Un-blocking an app between the kill and the reconnect settles the
        // debt at nothing. Charging for an app the user has since taken off the
        // list bills them for a rule that no longer exists.
        if (pending != null && awake && pending.packageName in settings.current.blockedPackages) {
            val owed = DrainMath.reconcileSeconds(
                pending = pending,
                nowWall = System.currentTimeMillis(),
                capSeconds = RECONCILE_CAP_SECONDS,
            )
            if (owed > 0) bank.spend(owed)
        }

        // Pick the scroll back up if it is still going — but as a guess, which
        // expires by itself. This used to start an open-ended meter on nothing
        // more than a stale preference, on the stated assumption that the next
        // window event would correct it. Nothing raises a window event for a
        // screen that is already drawn, so a phone sitting on its home screen
        // would keep paying for an Instagram nobody had open.
        if (awake) {
            val foreground = pending?.packageName ?: lastForegroundPackage ?: return
            // Not remembered up front: onForegroundApp does that, and only once
            // it knows this was a window worth remembering.
            onForegroundApp(foreground, className = null, confirmed = false)
        }
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

            // The screen going dark normally arrives as ACTION_SCREEN_OFF, and
            // that is what stops the meter. This is the backstop for when it
            // does not: a dropped broadcast, or an OEM that skips it, would
            // otherwise leave the meter billing a phone in a pocket for as long
            // as the CPU happened to stay awake.
            if (!power.isInteractive) {
                stopDrain()
                return
            }

            val now = SystemClock.uptimeMillis()

            // A drain nobody has vouched for gets a short run and no more.
            val guessed = guessedAt
            if (guessed != NOT_GUESSED && now - guessed >= GUESS_GRACE_MILLIS) {
                stopDrain()
                return
            }

            val seconds = meter.charge(now)

            if (seconds > 0) {
                val spent = bank.spend(seconds)
                val remaining = bank.balanceSeconds()

                if (spent < seconds || remaining <= 0) {
                    stopDrain()
                    lock(packageName)
                    return
                }

                persistProgress(packageName, now)

                // Redrawn only when the number visibly changes. Below an hour
                // that is every second, which is the point: a balance that
                // moves is the only way to see from the outside that the meter
                // is running at all.
                val label = formatBalance(remaining)
                if (label != lastDrainLabel) {
                    lastDrainLabel = label
                    notifications.showDraining(drainAppLabel, label)
                }

                if (settings.current.warnBeforeLock &&
                    !warningShown &&
                    remaining <= Settings.WARN_AT_SECONDS
                ) {
                    warningShown = true
                    toast("$label left — bank more or wrap up")
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
    private fun persistProgress(packageName: String, nowAwake: Long) {
        if (nowAwake - lastPersistedAt < PERSIST_EVERY_MILLIS) return
        lastPersistedAt = nowAwake
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
            balanceLabel = formatBalance(bank.balanceSeconds()),
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

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** How often the reconciler's marker is written while draining. */
        const val PERSIST_EVERY_MILLIS = 5_000L

        /** Ceiling on what an interrupted drain can cost when it is settled. */
        const val RECONCILE_CAP_SECONDS = 120

        /** Sentinel for a drain an event has vouched for. */
        const val NOT_GUESSED = -1L

        /**
         * How long a drain resumed from a remembered package may run before
         * something has to confirm it is really on screen.
         *
         * Long enough that a user coming back to a scroll touches the feed well
         * inside it, short enough that guessing wrong is a rounding error
         * against a bank that took push-ups to fill.
         */
        const val GUESS_GRACE_MILLIS = 30_000L

        /** Fastest a scroll is allowed to re-run the decision for one package. */
        const val HEARTBEAT_MIN_INTERVAL_MILLIS = 1_000L
    }
}
