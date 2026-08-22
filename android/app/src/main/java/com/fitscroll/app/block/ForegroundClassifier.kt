package com.fitscroll.app.block

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
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
 * A real app switch is narrower than it looks, and it takes two tests rather
 * than one:
 *
 *  1. **Whose window is it.** The user can only move to something with a
 *     launcher icon, or to their home screen. Everything else that flashes a
 *     window over the top is transient by definition.
 *
 *  2. **Is it an activity.** A launchable app can raise a window that is not an
 *     app switch either — a heads-up notification, a media control, a home
 *     screen widget expanding, an OEM surface inside the notification shade.
 *     Those report a *view* class where an activity would report its own class
 *     name, so asking the package manager to resolve the two as a component
 *     separates them. This is the test that stops pulling the shade down and
 *     closing it again from killing a live drain: the meter would stop on the
 *     way past, and since the app underneath never went away it raised no
 *     window event when the shade closed, so nothing ever started it again.
 *
 * Both lookups are cached because they run on every window event. Until the
 * first refresh lands nothing is treated as transient, which degrades to the
 * old behaviour rather than to an unmetered blocked app.
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

    /** Whether `package/class` resolves to a real activity. Bounded. */
    private val activityCache = ConcurrentHashMap<String, Boolean>()

    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    private val imeWatcher = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            packageWatcher,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
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
     *
     * @param className the window class the event reported, or null to skip the
     *   activity test. Null is passed for two quite different reasons: an event
     *   that carries no window class at all (a scroll, which says a package is
     *   on screen rather than that it just arrived), and any moment when no
     *   blocked app is actually being enforced. The activity test only ever
     *   protects a session in progress. Outside one, a class that fails to
     *   resolve — an alias, an odd embedding, something this misreads — would
     *   turn into a lock screen left sitting over an innocent app, and being
     *   wrong that way is worse than the leak the test is closing.
     */
    fun isTransientWindow(packageName: String, className: String? = null): Boolean {
        if (packageName in SYSTEM_UI_PACKAGES) return true
        if (!ready) return false
        if (packageName in imePackages) return true

        // Checked before the activity test and never subjected to it. Pressing
        // home has to stop the meter whatever the launcher calls its window.
        if (packageName in homePackages) return false

        if (packageName !in launchablePackages) return true

        return className != null && !resolvesToActivity(packageName, className)
    }

    /**
     * Whether `package/class` names an activity rather than a view.
     *
     * A miss is cached alongside a hit: the windows this is asked about are a
     * small, repeating set — one app's dialog, one launcher's widget host — and
     * re-asking the package manager about each of them on every event is a
     * binder round trip on the main thread.
     */
    private fun resolvesToActivity(packageName: String, className: String): Boolean {
        val key = "$packageName/$className"
        activityCache[key]?.let { return it }

        val resolved = runCatching {
            context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
            true
        }.getOrDefault(false)

        if (activityCache.size < ACTIVITY_CACHE_LIMIT) activityCache[key] = resolved
        return resolved
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
            // An app that was updated may have renamed or dropped an activity.
            activityCache.clear()
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

        /** Plenty for the handful of windows a phone actually cycles through. */
        const val ACTIVITY_CACHE_LIMIT = 256
    }
}
