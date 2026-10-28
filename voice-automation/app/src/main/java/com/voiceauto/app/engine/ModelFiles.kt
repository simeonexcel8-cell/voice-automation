package com.voiceauto.app.engine

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The .cact files ship inside the APK under assets/models/ and are copied to
 * private storage once, because the engine loads models from a file path.
 */
object ModelFiles {
    const val ASSET_DIR = "models"
    const val NEEDLE = "needle3.cact"
    const val WHISTLE = "whistle.cact"

    data class Paths(val needle: File, val whistle: File?)

    /** True when at least the Needle model is bundled in the APK. */
    fun bundled(context: Context): Boolean =
        context.assets.list(ASSET_DIR)?.contains(NEEDLE) == true

    /** Copies the models this tier needs (if not already copied) and returns their paths. */
    @Throws(IOException::class)
    fun prepare(context: Context, tier: DeviceTier): Paths {
        val available = context.assets.list(ASSET_DIR)?.toSet().orEmpty()
        val needleName = if (tier.needleAsset in available) tier.needleAsset else NEEDLE
        if (needleName !in available) {
            throw IOException("assets/$ASSET_DIR/$NEEDLE is missing — see README.md, step 3")
        }
        val needle = copyIfNeeded(context, needleName)
        val whistle = if (WHISTLE in available) copyIfNeeded(context, WHISTLE) else null
        return Paths(needle, whistle)
    }

    private fun copyIfNeeded(context: Context, name: String): File {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, name)
        val assetPath = "$ASSET_DIR/$name"
        // Assets are stored uncompressed (noCompress "cact"), so openFd gives the size.
        val size = context.assets.openFd(assetPath).use { it.length }
        if (target.exists() && target.length() == size) return target

        val tmp = File(dir, "$name.part")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 16) }
        }
        if (!tmp.renameTo(target)) throw IOException("could not move $name into place")
        return target
    }
}
