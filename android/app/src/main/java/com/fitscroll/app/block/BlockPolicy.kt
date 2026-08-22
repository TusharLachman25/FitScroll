package com.fitscroll.app.block

/** What the service should do about the app that just came to the front. */
sealed interface BlockAction {

    /**
     * Leave everything exactly as it is.
     *
     * Raised for windows that appear *over* the current app without replacing
     * it — the keyboard, a share sheet, a permission dialog. Treating one of
     * those as an app switch is what used to stop the meter mid-scroll.
     */
    data object Ignore : BlockAction

    /** FitScroll's own UI. Stop billing, but leave the lock window alone. */
    data object StandDown : BlockAction

    /** An unrelated app: the user backed off, so drop the lock and stop billing. */
    data object Release : BlockAction

    /** A blocked app with credit left. */
    data class Drain(val packageName: String, val remainingSeconds: Int) : BlockAction

    /** A blocked app with an empty bank. */
    data class Lock(val packageName: String) : BlockAction
}

/**
 * The whole blocking decision, as a function of what is on screen.
 *
 * Pulled out of the service so it can be exercised without an accessibility
 * framework, a clock or a device. The service is left holding only the parts
 * that genuinely need Android: classifying the window and carrying the action
 * out.
 */
object BlockPolicy {

    fun decide(
        foreground: String,
        ownPackage: String,
        blockedPackages: Set<String>,
        isTransientWindow: Boolean,
        balanceSeconds: Int,
        isRetired: Boolean = false,
    ): BlockAction = when {
        // Ahead of everything, including the blocked-app test below.
        //
        // A withdrawn build must stop enforcing before it stops doing anything
        // else. Retiring a build that kept its lock screen would strand people
        // behind it with no way to earn their way out - the exact opposite of
        // what withdrawing it is for. Release also drops any lock already on
        // screen, so a build retired mid-scroll lets go rather than freezing.
        isRetired -> BlockAction.Release

        // Checked before anything else. A blocked app is never a transient
        // window, and misclassifying one would hand out unmetered screen time,
        // which is the failure this whole class exists to prevent.
        foreground in blockedPackages ->
            if (balanceSeconds <= 0) BlockAction.Lock(foreground)
            else BlockAction.Drain(foreground, balanceSeconds)

        isTransientWindow -> BlockAction.Ignore

        foreground == ownPackage -> BlockAction.StandDown

        else -> BlockAction.Release
    }
}
