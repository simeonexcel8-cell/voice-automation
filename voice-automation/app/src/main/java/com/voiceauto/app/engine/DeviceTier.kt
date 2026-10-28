package com.voiceauto.app.engine

import android.app.ActivityManager
import android.content.Context

/**
 * Phone tiers from the architecture doc. The numbers are starting points:
 * measure peak RAM and latency on real phones and adjust.
 */
enum class DeviceTier(
    /** Model file to prefer in assets/models; falls back to needle3.cact if missing. */
    val needleAsset: String,
    /** How long the engine stays loaded after the last command. */
    val keepLoadedMs: Long,
) {
    LOW(needleAsset = "needle3-low.cact", keepLoadedMs = 20_000),
    MID(needleAsset = "needle3.cact", keepLoadedMs = 120_000),
    HIGH(needleAsset = "needle3.cact", keepLoadedMs = 600_000);

    companion object {
        private const val GB = 1024L * 1024L * 1024L

        fun detect(context: Context): DeviceTier {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return when {
                am.isLowRamDevice || info.totalMem <= 3_200_000_000L -> LOW
                info.totalMem <= 7 * GB -> MID
                else -> HIGH
            }
        }
    }
}
