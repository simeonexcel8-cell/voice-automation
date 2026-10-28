package com.voiceauto.app.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import androidx.core.os.BundleCompat
import org.json.JSONObject
import java.nio.ByteOrder
import com.voiceauto.app.engine.EngineProtocol as P

/**
 * Runs the Cactus engine in the ":engine" process.
 *
 * - All engine calls happen on one HandlerThread, so they are serialized.
 * - When the app unbinds (idle timeout or memory pressure), the service is
 *   destroyed and the whole process is killed, which returns all native memory.
 */
class EngineService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    private var loaded = false
    private var hasWhistle = false
    private var initSystem: String? = null
    private var initTools: String? = null
    private var initAt = 0L
    private var prefixTokens = 0

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("needle-engine", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
        // The engine has no unload call; ending the process frees the models.
        Process.killProcess(Process.myPid())
    }

    private fun handle(msg: Message) {
        val request = msg.data
        val reply = Bundle()
        try {
            ensureLoaded()
            ensureInit(
                system = request.getString(P.KEY_SYSTEM).orEmpty(),
                tools = request.getString(P.KEY_TOOLS) ?: "[]",
            )
            reply.putInt(P.KEY_PREFIX_TOKENS, prefixTokens)
            if (msg.what == P.MSG_PROCESS) process(request, reply)
        } catch (t: Throwable) {
            Log.e(TAG, "engine call failed", t)
            reply.putString(P.KEY_ERROR, t.message ?: t.javaClass.simpleName)
        }
        val answer = Message.obtain(null, msg.what, msg.arg1, 0).apply { data = reply }
        try {
            msg.replyTo?.send(answer)
        } catch (e: RemoteException) {
            Log.w(TAG, "app went away before the reply", e)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val tier = DeviceTier.detect(this)
        val paths = ModelFiles.prepare(this, tier)
        paths.whistle?.let {
            NeedleNative.nativeLoad(it.absolutePath)
            hasWhistle = true
        }
        NeedleNative.nativeLoad(paths.needle.absolutePath)
        loaded = true
        Log.i(TAG, "models loaded for tier $tier (whistle=$hasWhistle)")
    }

    /**
     * needle_init prefills the system facts and tool schemas. It is redone when
     * the tools change, or when the facts change and the last init is over
     * 5 minutes old (so the date and time the model sees stay roughly current).
     */
    private fun ensureInit(system: String, tools: String) {
        val now = SystemClock.elapsedRealtime()
        val toolsChanged = tools != initTools
        val factsStale = system != initSystem && now - initAt > REINIT_AFTER_MS
        if (initTools != null && !toolsChanged && !factsStale) return
        prefixTokens = NeedleNative.nativeInit(system, tools)
        initSystem = system
        initTools = tools
        initAt = now
    }

    private fun process(request: Bundle, reply: Bundle) {
        val text: String = request.getString(P.KEY_TEXT) ?: run {
            val samples = readAudio(request)
            check(hasWhistle) { "whistle.cact is not bundled, so voice input is off — type the command" }
            val json = NeedleNative.nativeTranscribe(samples, "en", request.getString(P.KEY_KEYWORDS))
            JSONObject(json.decodeToString()).optString("text").trim()
        }
        reply.putString(P.KEY_TRANSCRIPT, text)
        if (text.isEmpty()) return

        // Commands are independent: no chat history, so every turn starts clean.
        NeedleNative.nativeReset()
        val maxNewTokens = request.getInt(P.KEY_MAX_NEW_TOKENS, DEFAULT_MAX_NEW_TOKENS)
        val response = NeedleNative.nativeComplete(text, maxNewTokens).decodeToString()
        reply.putString(P.KEY_RESPONSE, response)
    }

    private fun readAudio(request: Bundle): FloatArray {
        // BundleCompat: the typed getParcelable(key, Class) needs API 33; minSdk is 29.
        val shm = BundleCompat.getParcelable(request, P.KEY_AUDIO, SharedMemory::class.java)
            ?: error("no audio or text in the request")
        val count = request.getInt(P.KEY_SAMPLE_COUNT)
        try {
            val buffer = shm.mapReadOnly()
            try {
                val samples = FloatArray(count)
                buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(samples)
                return samples
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            shm.close()
        }
    }

    private companion object {
        const val TAG = "EngineService"
        const val REINIT_AFTER_MS = 5 * 60_000L
        const val DEFAULT_MAX_NEW_TOKENS = 256
    }
}
