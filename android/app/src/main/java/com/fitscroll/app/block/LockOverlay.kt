package com.fitscroll.app.block

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.fitscroll.app.R

/**
 * The lock screen, drawn as a window owned by the accessibility service.
 *
 * This replaced launching an activity, which was the wrong tool twice over.
 * Android 10+ refuses background activity launches unless the app holds the
 * overlay permission, so on a fresh install the lock silently never appeared
 * and all the user saw was being bounced to the home screen. A window of type
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] is granted to
 * accessibility services directly and needs no permission at all, so the lock
 * now shows on every install, configured or not.
 *
 * Showing is idempotent. The old activity path guarded against duplicate window
 * events with a time-based debounce, which also swallowed a deliberate re-open
 * a second later and let the blocked app straight through on the second try.
 * A window that is already up simply stays up, so no such guard is needed.
 */
class LockOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager =
        service.getSystemService(WindowManager::class.java)

    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(
        appLabel: String,
        balanceLabel: String,
        onEarn: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        if (view != null) return

        val themed = ContextThemeWrapper(service, R.style.Theme_FitScroll)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)

        root.findViewById<TextView>(R.id.lock_title).text = "$appLabel is locked"
        root.findViewById<TextView>(R.id.lock_roast).text = ROASTS.random()
        root.findViewById<TextView>(R.id.lock_balance).text = balanceLabel
        root.findViewById<TextView>(R.id.lock_rate).text =
            "One push-up buys one minute.\nEvery minute you bank lasts 24 hours."

        root.findViewById<Button>(R.id.lock_earn).setOnClickListener {
            hide()
            onEarn()
        }
        root.findViewById<Button>(R.id.lock_dismiss).setOnClickListener {
            hide()
            onDismiss()
        }

        // Back dismisses rather than being swallowed. The enforcement already
        // happened - the user was ejected from the blocked app before this
        // window went up - so trapping them here would only risk a stuck
        // overlay covering the whole phone if anything went wrong.
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hide()
                onDismiss()
                true
            } else {
                false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable so the window receives the back key. LAYOUT_IN_SCREEN
            // plus NO_LIMITS lets it cover the status and navigation bars, or
            // the blocked app stays visible in the strips above and below.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )

        runCatching {
            windowManager.addView(root, params)
            view = root
            root.requestFocus()
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeView(current) }
    }

    private companion object {
        /**
         * Shown one at a time on the lock screen.
         *
         * Aimed at the decision rather than the person: the point is to make
         * the trade visible at the moment it is being made, not to make anyone
         * feel worse about their body.
         */
        val ROASTS = listOf(
            "The feed will still be there in twenty push-ups.",
            "You wrote these rules. Past you was sharper than present you.",
            "Zero banked. The floor is right there.",
            "You have time to scroll, but not to push. Interesting.",
            "Twenty reps is ninety seconds. You have spent longer picking a filter.",
            "You are not blocked. You are just broke.",
            "Currency: push-ups. Balance: nothing. Do the maths.",
            "The algorithm has not missed you. Your chest has.",
            "This is the part where you get down on the floor. Or give up.",
            "Nothing in the bank, nothing on the screen. That was the deal.",
            "Your thumb is in better shape than your triceps.",
            "Somebody out there is doing push-ups instead of reading this.",
        )
    }
}
