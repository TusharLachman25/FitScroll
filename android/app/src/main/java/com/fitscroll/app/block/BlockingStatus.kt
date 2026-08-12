package com.fitscroll.app.block

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.text.TextUtils

/**
 * Checks and shortcuts for the two permissions FitScroll cannot grant itself.
 *
 * Android deliberately requires both of these to be toggled by hand in system
 * Settings; there is no runtime prompt for either, so onboarding has to send
 * the user there and then re-check on resume.
 */
object BlockingStatus {

    /**
     * True when the user has switched FitScroll on under Accessibility.
     *
     * Read from Settings.Secure rather than a flag the service sets itself,
     * because the system can stop the service without our code running - after
     * a crash, an OEM battery sweep, or the user toggling it off - and a stale
     * in-process flag would then claim protection that is not there.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, FitScrollAccessibilityService::class.java)
        val enabled = AndroidSettings.Secure.getString(
            context.contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component == expected) return true
        }
        return false
    }

    /**
     * True when FitScroll may draw over other apps.
     *
     * Not required for blocking. The lock screen is an accessibility overlay,
     * a window type the service is granted directly. This permission only
     * governs whether the lock's "earn minutes" button can open the camera,
     * since Android 10+ refuses background activity launches without it.
     */
    fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
