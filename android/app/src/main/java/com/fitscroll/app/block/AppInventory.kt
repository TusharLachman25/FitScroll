package com.fitscroll.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Enumerates apps the user could plausibly want to block.
 *
 * Only launchable apps are listed, resolved through a `<queries>` manifest
 * declaration rather than QUERY_ALL_PACKAGES. The narrower permission is enough
 * to build this picker and avoids claiming visibility over every package on the
 * device.
 */
object AppInventory {

    suspend fun launchableApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        pm.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            // Our own id is asked for rather than listed: debug builds carry a
            // suffix, and a hardcoded pair goes stale the moment either moves.
            .filterNot { it.packageName == context.packageName }
            .filterNot { it.packageName in EXCLUDED_PACKAGES }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    icon = runCatching {
                        // Rasterising here rather than in composition keeps the
                        // list scroll smooth; icon packs can be surprisingly
                        // expensive to inflate.
                        pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Resolves a package to its display name, falling back to the raw id. */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private const val ICON_PX = 128

    private val EXCLUDED_PACKAGES = setOf(
        // Locking Settings would strand the user with no way to switch the
        // accessibility service back off.
        "com.android.settings",
    )
}
