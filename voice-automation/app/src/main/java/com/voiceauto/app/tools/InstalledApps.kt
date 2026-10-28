package com.voiceauto.app.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Launchable apps on the phone, found through the <queries> launcher filter in the
 * manifest (no QUERY_ALL_PACKAGES needed). Their names feed two places: the
 * open_app enum for Needle, and Whistle's keyword biasing so names like
 * "Opay" or "Moniepoint" are heard correctly.
 */
class InstalledApps(private val context: Context) {

    /** Label → package name, sorted by label. */
    @Volatile var apps: Map<String, String> = emptyMap()
        private set

    fun refresh() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        apps = activities
            .filter { it.activityInfo.packageName != context.packageName }
            .associate { it.loadLabel(pm).toString().trim() to it.activityInfo.packageName }
            .filterKeys { it.isNotEmpty() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    /** Finds an app by the name the model returned: exact, then prefix, then contains. */
    fun find(label: String): Pair<String, String>? {
        val wanted = label.trim()
        apps.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.let { return it.toPair() }
        apps.entries.firstOrNull { it.key.startsWith(wanted, ignoreCase = true) }?.let { return it.toPair() }
        return apps.entries.firstOrNull { it.key.contains(wanted, ignoreCase = true) }?.toPair()
    }

    /** Names for Whistle keyword biasing, one per line. */
    fun keywords(limit: Int = 80): String = apps.keys.take(limit).joinToString("\n")
}
