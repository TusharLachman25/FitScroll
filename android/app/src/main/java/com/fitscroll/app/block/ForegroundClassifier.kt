package com.fitscroll.app.block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Decides whether a window belongs to an app the user actually switched to.
 *
 * Accessibility reports a window-state change for anything that takes the
 * screen, not just for app switches. The keyboard, the share sheet, an autofill
 * popup, a runtime-permission dialog and the notification shade all raise one
 * with their own package name while the app underneath is still very much in
 * front. The service used to read every one of those as "the user left", which
 * stopped the meter and hid the lock — so typing a comment on Instagram was
 * free, and so was opening the share sheet.
 *
 * A real app switch is narrower than it looks: the user can only move to
 * something with a launcher icon, or to their home screen. Everything else that
 * flashes a window over the top is transient by definition, so that is the test
 * used here.
 *
 * The lookup is cached because it runs on every window event. Until the first
 * refresh lands nothing is treated as transient, which degrades to the old
 * behaviour rather than to an unmetered blocked app.
 */
class ForegroundClassifier(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var launchablePackages: Set<String> = emptySet()

    @Volatile
    private var homePackages: Set<String> = emptySet()

    @Volatile
    private var imePackages: Set<String> = emptySet()

    /** False until the first scan lands, so an empty cache never hides an app. */
    @Volatile
    private var ready: Boolean = false

    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    private val imeWatcher = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        context.registerReceiver(
            packageWatcher,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
        )

        // Switching keyboard is a Settings.Secure edit, not a package change,
        // so it needs its own watcher or a newly chosen IME would be mistaken
        // for an app switch until the next reboot.
        listOf(
            Settings.Secure.DEFAULT_INPUT_METHOD,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ).forEach { key ->
            runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    imeWatcher,
                )
            }
        }

        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(packageWatcher) }
        runCatching { context.contentResolver.unregisterContentObserver(imeWatcher) }
        scope.cancel()
    }

    /**
     * True when this window is sitting on top of the real foreground app rather
     * than replacing it.
     */
    fun isTransientWindow(packageName: String): Boolean {
        if (packageName in SYSTEM_UI_PACKAGES) return true
        if (!ready) return false
        if (packageName in imePackages) return true
        return packageName !in launchablePackages && packageName !in homePackages
    }

    private fun refresh() {
        scope.launch {
            val pm = context.packageManager

            val launchable = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
            }.getOrDefault(emptySet())

            // Every installed launcher counts, not just the current default:
            // pressing home has to stop the meter whichever one answers.
            val home = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    0,
                ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
            }.getOrDefault(emptySet())

            val imes = runCatching {
                context.getSystemService(InputMethodManager::class.java)
                    .enabledInputMethodList
                    .mapTo(mutableSetOf()) { it.packageName }
            }.getOrDefault(emptySet())

            // A scan that came back with nothing means the query failed rather
            // than that the phone has no apps. Keeping the previous cache is
            // safer than declaring every window transient.
            if (launchable.isEmpty()) return@launch

            launchablePackages = launchable
            homePackages = home
            imePackages = imes
            ready = true
        }
    }

    private companion object {
        /**
         * The shade, quick settings and the volume dialog all arrive under this
         * name. Filtered before the cache is consulted because they are the one
         * case that has to work from the very first event.
         */
        val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
        )
    }
}
