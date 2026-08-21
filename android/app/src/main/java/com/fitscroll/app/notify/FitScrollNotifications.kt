package com.fitscroll.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fitscroll.app.MainActivity
import com.fitscroll.app.R

/**
 * The two things FitScroll has to say while nobody is looking at its UI.
 *
 * A toast could say the first one, and used to: the balance at the moment a
 * blocked app opens. But the whole thesis of the app is that the cost should be
 * visible *while* it is being paid, and a toast is gone in two seconds. An
 * ongoing notification carries the number for the length of the session.
 *
 * Every post is best-effort. Notifications are a runtime permission on Android
 * 13+, and being refused must degrade to the toasts and the lock screen rather
 * than to a crash inside an accessibility service, which would take the
 * blocking down with it.
 */
class FitScrollNotifications(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DRAIN,
                    context.getString(R.string.channel_drain),
                    // Low: this is a readout, not an event. It should sit in the
                    // shade next to the balance, never buzz mid-scroll.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_drain_description)
                    setShowBadge(false)
                },
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EXPIRY,
                    context.getString(R.string.channel_expiry),
                    // Default: this one is worth interrupting for. It is the
                    // only warning that reps already done are about to be lost.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_expiry_description)
                },
            )
        }
    }

    /** The live balance, for as long as a blocked app is on screen. */
    fun showDraining(appLabel: String, balanceLabel: String) {
        post(
            id = ID_DRAIN,
            notification = base(CHANNEL_DRAIN)
                .setContentTitle(context.getString(R.string.drain_title, balanceLabel))
                .setContentText(context.getString(R.string.drain_text, appLabel))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build(),
        )
    }

    fun hideDraining() {
        runCatching { manager.cancel(ID_DRAIN) }
    }

    /** Minutes already earned are about to evaporate unspent. */
    fun showExpiring(expiringLabel: String) {
        post(
            id = ID_EXPIRY,
            notification = base(CHANNEL_EXPIRY)
                .setContentTitle(context.getString(R.string.expiry_title, expiringLabel))
                .setContentText(context.getString(R.string.expiry_text))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun base(channelId: String): Notification.Builder =
        Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_shield)
            .setContentIntent(openApp())

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_START_WORKOUT, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(id: Int, notification: Notification) {
        // Throws when the permission is missing on some OEM builds rather than
        // no-opping, and this runs inside the accessibility service.
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_DRAIN = "drain"
        const val CHANNEL_EXPIRY = "expiry"

        private const val ID_DRAIN = 1
        private const val ID_EXPIRY = 2
    }
}
