package com.fitscroll.app.release

import android.content.Context
import com.fitscroll.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the published notice says about this build. */
data class ReleaseStatus(
    /** True when this build has been withdrawn and must stop enforcing. */
    val retired: Boolean = false,
    /** Why, in the author's words. Shown verbatim. */
    val message: String? = null,
    /** Where to get the supported build, if the notice named somewhere. */
    val updateUrl: String? = null,
    /** Wall-clock millis of the last successful check, 0 when never. */
    val checkedAt: Long = 0L,
)

/**
 * Whether a build is still supported, decided by a file the author publishes.
 *
 * The arithmetic is a version floor rather than an on/off switch. Retiring by
 * version means one number withdraws every build below it at once, and a build
 * cannot be retired by a notice written before it existed - which an "off" flag
 * would do the moment it was left switched on.
 */
object ReleasePolicy {

    fun isRetired(minVersionCode: Int, installedVersionCode: Int): Boolean =
        installedVersionCode < minVersionCode

    /**
     * Reads a notice. Returns null when it is unreadable, which is treated as
     * no answer rather than as a retirement.
     */
    fun parse(json: String, installedVersionCode: Int, now: Long): ReleaseStatus? = runCatching {
        val root = JSONObject(json)
        val minVersionCode = root.getInt("minVersionCode")
        ReleaseStatus(
            retired = isRetired(minVersionCode, installedVersionCode),
            message = root.optString("message").takeIf { it.isNotBlank() },
            updateUrl = root.optString("updateUrl").takeIf { it.isNotBlank() },
            checkedAt = now,
        )
    }.getOrNull()
}

/**
 * Checks the published notice, and remembers the answer.
 *
 * This is the one thing in FitScroll that touches the network, and it exists
 * because a build handed to someone by hand has no other way of being told it
 * has been superseded. The request is a plain GET of a static public file: no
 * query string, no headers, no identifier, no body. It says that somebody
 * fetched a file, and nothing else.
 *
 * Two rules make it safe to ship:
 *
 * It **fails open**. A failed fetch, an unreadable notice, or a build with no
 * notice URL at all leaves the last known answer standing - and the last known
 * answer starts as "supported". A phone that is offline, or behind a firewall
 * that eats the request, keeps working. The cost is that the switch is
 * defeatable by blocking one domain, which is the same bargain the rest of the
 * app makes: this is a commitment device, not DRM.
 *
 * And retirement **unblocks everything**. A retired build must never keep
 * enforcing, or withdrawing one would strand its users behind a lock screen
 * with no way to earn their way out. That is enforced in BlockPolicy, ahead of
 * every other test.
 */
class ReleaseGate internal constructor(
    context: Context,
    private val statusUrl: String,
    private val installedVersionCode: Int,
    private val fetch: suspend (String) -> String?,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReleaseStatus> = _state.asStateFlow()

    /**
     * Re-reads the notice, at most once per [MIN_INTERVAL_MILLIS].
     *
     * @param force skips the interval, for a deliberate check.
     */
    suspend fun refresh(force: Boolean = false) {
        if (statusUrl.isBlank()) return

        val now = System.currentTimeMillis()
        val since = now - _state.value.checkedAt
        // A clock moved backwards makes `since` negative, which would otherwise
        // suppress every future check until the clock caught up again.
        if (!force && since in 0 until MIN_INTERVAL_MILLIS) return

        val body = fetch(statusUrl) ?: return
        val parsed = ReleasePolicy.parse(body, installedVersionCode, now) ?: return

        store(parsed)
        _state.value = parsed
    }

    private fun load(): ReleaseStatus = ReleaseStatus(
        retired = prefs.getBoolean(KEY_RETIRED, false),
        message = prefs.getString(KEY_MESSAGE, null),
        updateUrl = prefs.getString(KEY_UPDATE_URL, null),
        checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L),
    )

    private fun store(status: ReleaseStatus) {
        prefs.edit()
            .putBoolean(KEY_RETIRED, status.retired)
            .putString(KEY_MESSAGE, status.message)
            .putString(KEY_UPDATE_URL, status.updateUrl)
            .putLong(KEY_CHECKED_AT, status.checkedAt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fitscroll_release"
        private const val KEY_RETIRED = "retired"
        private const val KEY_MESSAGE = "message"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_CHECKED_AT = "checked_at"

        /** Checked at most this often. A retirement is not an emergency. */
        const val MIN_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L

        @Volatile
        private var instance: ReleaseGate? = null

        fun get(context: Context): ReleaseGate =
            instance ?: synchronized(this) {
                instance ?: ReleaseGate(
                    context = context,
                    statusUrl = BuildConfig.RELEASE_STATUS_URL,
                    installedVersionCode = BuildConfig.VERSION_CODE,
                    fetch = ::httpGet,
                ).also { instance = it }
            }

        internal fun resetForTests() = synchronized(this) { instance = null }

        /**
         * A plain GET, carrying nothing that identifies the caller.
         *
         * Returns null on any failure, which the caller reads as "no answer"
         * rather than as a retirement.
         */
        private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = TIMEOUT_MILLIS
                    connection.readTimeout = TIMEOUT_MILLIS
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        null
                    } else {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }

        private const val TIMEOUT_MILLIS = 10_000
    }
}
