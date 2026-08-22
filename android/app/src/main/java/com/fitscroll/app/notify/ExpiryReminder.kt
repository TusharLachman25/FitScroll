package com.fitscroll.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.ui.formatBalance

/**
 * The nudge that fires shortly before banked minutes evaporate.
 *
 * The home screen already shows what is about to expire, but the home screen is
 * the one place you would already know. Minutes cost push-ups, and losing them
 * unspent because you were not looking at the right app is what makes the 24h
 * rule feel like a punishment rather than a mechanic.
 *
 * Inexact by design. A reminder that lands within a few minutes of the hour
 * mark is exactly as useful as one that lands on it, and exact alarms are a
 * separately-granted permission that this does not need.
 */
object ExpiryReminder {

    /** How far ahead of expiry the nudge lands. */
    const val LEAD_MILLIS = 60L * 60L * 1000L

    /** Slack given to the OS to batch the alarm with whatever else it is doing. */
    private const val WINDOW_MILLIS = 10L * 60L * 1000L

    /** A moment past expiry, so the batch has actually died when we look. */
    private const val SETTLE_MILLIS = 30L * 1000L

    private const val REQUEST_CODE = 100

    /**
     * Arms the next alarm, or cancels it when the bank is empty.
     *
     * When the nudge point has already passed, this arms for the moment the
     * batch dies instead. That is what keeps the chain going: the alarm that
     * fires then finds a fresh expiry instant and re-arms from it,
     * where stopping would leave the reminder working exactly once.
     */
    fun schedule(context: Context, nextExpiryAt: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context)

        if (nextExpiryAt == null) {
            runCatching { alarms.cancel(intent) }
            return
        }

        val now = System.currentTimeMillis()
        val nudgeAt = nextExpiryAt - LEAD_MILLIS
        val triggerAt = if (nudgeAt > now) nudgeAt else nextExpiryAt + SETTLE_MILLIS

        if (triggerAt <= now) {
            runCatching { alarms.cancel(intent) }
            return
        }

        runCatching {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_MILLIS, intent)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ExpiryReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Posts the nudge and re-arms the next one.
 *
 * Alarms do not survive a reboot, but nothing needs to restore them by hand:
 * the accessibility service is started by the system when the user unlocks
 * after boot, which builds the application, which schedules from the ledger it
 * has just loaded.
 */
class ExpiryReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val bank = BankRepository.get(context)

        // Purges whatever died while nothing was running, so the numbers below
        // describe the bank as it is now rather than as it was at the last
        // mutation.
        bank.refresh()
        val state = bank.state.value

        val expiring = state.expiringWithinHourSeconds
        if (expiring > 0) {
            FitScrollNotifications(context).showExpiring(formatBalance(expiring))
        }

        // Re-armed whether or not anything was posted. This is the only place
        // the chain continues from once the app is not being looked at.
        ExpiryReminder.schedule(context, state.nextExpiryAt)
    }
}
