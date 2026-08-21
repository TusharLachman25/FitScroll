package com.fitscroll.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Packages the drain and the lock screen apply to. */
    val blockedPackages: Set<String> = setOf(INSTAGRAM_PACKAGE),
    /** Ceiling on the bank, in minutes. Defaults to a full 24h of screen time. */
    val bankCapMinutes: Int = DEFAULT_CAP_MINUTES,
    /** How exacting the rep counter is, 1 (lenient) to 5 (strict). */
    val strictness: Int = DEFAULT_STRICTNESS,
    /** Whether to warn shortly before the balance runs out. */
    val warnBeforeLock: Boolean = true,
    /**
     * Whether the one-time notification permission prompt has been shown.
     *
     * Asked once and never again. Notifications are a nicety here - the lock
     * screen and the toasts work without them - and an app about self-control
     * that nags for permissions on every launch has picked the wrong fight.
     */
    val notificationsRequested: Boolean = false,
) {
    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val DEFAULT_CAP_MINUTES = 1440
        const val DEFAULT_STRICTNESS = 3

        /** Bank cap bounds offered in Settings: 15 minutes up to a full day. */
        const val MIN_CAP_MINUTES = 15
        const val MAX_CAP_MINUTES = 1440

        /** How much notice you get before the lock lands, in seconds. */
        const val WARN_AT_SECONDS = 60
    }
}

/**
 * User preferences, shared by the UI and the accessibility service.
 *
 * Singleton for the same reason as [BankRepository]: the service reads the
 * blocked-package set on every foreground change and must observe edits made in
 * Settings immediately, without a round trip through storage.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val ownPackage = context.applicationContext.packageName

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun setBlockedPackages(packages: Set<String>) = update {
        // Blocking ourselves would make the lock screen unreachable and leave
        // the only escape hatch in Android's own settings. Asked for rather
        // than hardcoded, since the debug build carries a suffix.
        it.copy(blockedPackages = packages - ownPackage)
    }

    fun setBankCapMinutes(minutes: Int) = update {
        it.copy(
            bankCapMinutes = minutes.coerceIn(
                Settings.MIN_CAP_MINUTES,
                Settings.MAX_CAP_MINUTES,
            ),
        )
    }

    fun setStrictness(level: Int) = update { it.copy(strictness = level.coerceIn(1, 5)) }

    fun setWarnBeforeLock(enabled: Boolean) = update { it.copy(warnBeforeLock = enabled) }

    fun markNotificationsRequested() = update { it.copy(notificationsRequested = true) }

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit()
            .putStringSet(KEY_BLOCKED, next.blockedPackages)
            .putInt(KEY_CAP_MINUTES, next.bankCapMinutes)
            .putInt(KEY_STRICTNESS, next.strictness)
            .putBoolean(KEY_WARN, next.warnBeforeLock)
            .putBoolean(KEY_NOTIFICATIONS_ASKED, next.notificationsRequested)
            .apply()
        _state.value = next
    }

    private fun load(): Settings {
        val defaults = Settings()
        return Settings(
            // getStringSet returns a shared instance that must not be mutated,
            // so it is copied defensively.
            blockedPackages = prefs.getStringSet(KEY_BLOCKED, null)?.toSet()
                ?: defaults.blockedPackages,
            bankCapMinutes = prefs.getInt(KEY_CAP_MINUTES, defaults.bankCapMinutes),
            strictness = prefs.getInt(KEY_STRICTNESS, defaults.strictness),
            warnBeforeLock = prefs.getBoolean(KEY_WARN, defaults.warnBeforeLock),
            notificationsRequested = prefs.getBoolean(
                KEY_NOTIFICATIONS_ASKED,
                defaults.notificationsRequested,
            ),
        )
    }

    companion object {
        private const val PREFS_NAME = "fitscroll_settings"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_CAP_MINUTES = "bank_cap_minutes"
        private const val KEY_STRICTNESS = "strictness"
        private const val KEY_WARN = "warn_before_lock"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_requested"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
