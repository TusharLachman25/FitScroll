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

    /**
     * @param canLaunchCamera whether the service may start an activity from
     *   here. Without the overlay permission Android drops a background
     *   activity launch on the floor, so the primary button would appear to do
     *   nothing at all; when that is the case it says what it can actually
     *   deliver instead of making a promise the system will refuse.
     */
    fun show(
        appLabel: String,
        balanceLabel: String,
        canLaunchCamera: Boolean,
        onEarn: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        if (view != null) return

        val themed = ContextThemeWrapper(service, R.style.Theme_FitScroll)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)
        val resources = themed.resources

        root.findViewById<TextView>(R.id.lock_title).text =
            resources.getString(R.string.lock_title, appLabel)
        root.findViewById<TextView>(R.id.lock_roast).text =
            resources.getStringArray(R.array.lock_roasts).random()
        root.findViewById<TextView>(R.id.lock_balance).text = balanceLabel

        root.findViewById<Button>(R.id.lock_earn).apply {
            setText(if (canLaunchCamera) R.string.lock_earn else R.string.lock_earn_manual)
            setOnClickListener {
                hide()
                onEarn()
            }
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

    /**
     * Takes the window down, and only forgets it if that worked.
     *
     * Dropping the reference first meant a failed removal stranded the overlay
     * on screen covering the whole phone, with nothing left holding the view to
     * try again. A window that is already detached throws too, which is the
     * benign case - hence the recheck rather than trusting the result.
     */
    fun hide() {
        val current = view ?: return
        val removed = runCatching { windowManager.removeView(current) }.isSuccess
        if (removed || !current.isAttachedToWindow) view = null
    }
}
