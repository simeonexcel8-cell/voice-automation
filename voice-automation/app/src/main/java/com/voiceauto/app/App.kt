package com.voiceauto.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.voiceauto.app.engine.DeviceTier
import com.voiceauto.app.engine.EngineClient
import com.voiceauto.app.policy.Policy
import com.voiceauto.app.tools.InstalledApps
import com.voiceauto.app.tools.NotesStore
import com.voiceauto.app.tools.ToolRegistry

/**
 * Application.onCreate also runs in the ":engine" process, so everything here is
 * lazy: the engine process never builds the UI-side objects.
 */
class App : Application() {
    val tier: DeviceTier by lazy { DeviceTier.detect(this) }
    val installedApps: InstalledApps by lazy { InstalledApps(this).apply { refresh() } }
    val notes: NotesStore by lazy { NotesStore(this) }
    val registry: ToolRegistry by lazy { ToolRegistry(installedApps, notes) }
    val policy: Policy by lazy { Policy(registry) }
    val engine: EngineClient by lazy { EngineClient(this, tier) }

    private var engineCreated = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The app went to the background or memory is tight: free the models now.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && engineCreated) engine.release()
    }

    /** Marks that the UI touched the engine, so onTrimMemory doesn't create it just to release it. */
    fun engineInUse(): EngineClient {
        engineCreated = true
        return engine
    }
}
