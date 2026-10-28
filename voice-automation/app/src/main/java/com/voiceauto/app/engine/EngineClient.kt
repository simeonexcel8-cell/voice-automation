package com.voiceauto.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SharedMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.voiceauto.app.engine.EngineProtocol as P

/** What the app sends to the engine for one command. Exactly one of text or audio is set. */
class EngineRequest(
    val system: String,
    val toolsJson: String,
    val keywords: String?,
    val text: String? = null,
    val audio: FloatArray? = null,
)

/** What comes back. [response] is Needle's JSON envelope; [error] is set on failure. */
data class EngineResult(
    val transcript: String?,
    val response: String?,
    val error: String?,
)

/**
 * App-side handle on EngineService. Binds on demand, unbinds after the tier's idle
 * timeout (which ends the :engine process), and fails cleanly if that process dies.
 * Call from the main thread.
 */
class EngineClient(
    private val context: Context,
    private val tier: DeviceTier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bindLock = Mutex()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        // Copy the Bundle: the Message is recycled after this callback.
        pending.remove(msg.arg1)?.complete(Bundle(msg.data))
        true
    })

    /** Loads the models and tool schemas ahead of the first command. */
    suspend fun warmUp(request: EngineRequest): String? =
        call(P.MSG_WARM_UP, request).getString(P.KEY_ERROR)

    suspend fun process(request: EngineRequest): EngineResult {
        val reply = call(P.MSG_PROCESS, request)
        return EngineResult(
            transcript = reply.getString(P.KEY_TRANSCRIPT),
            response = reply.getString(P.KEY_RESPONSE),
            error = reply.getString(P.KEY_ERROR),
        )
    }

    /** Unbinds now, which ends the engine process. Used on memory pressure. */
    fun release() {
        idleJob?.cancel()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        failAll("engine was released")
    }

    private suspend fun call(what: Int, request: EngineRequest): Bundle {
        idleJob?.cancel()
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<Bundle>()
        pending[id] = deferred

        val samples = request.audio
        var audio: SharedMemory? = null
        try {
            val target = connect()
            val shm = samples?.let { toSharedMemory(it) }
            audio = shm
            val msg = Message.obtain(null, what, id, 0).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString(P.KEY_SYSTEM, request.system)
                    putString(P.KEY_TOOLS, request.toolsJson)
                    putString(P.KEY_KEYWORDS, request.keywords)
                    request.text?.let { putString(P.KEY_TEXT, it) }
                    if (shm != null) {
                        putParcelable(P.KEY_AUDIO, shm)
                        putInt(P.KEY_SAMPLE_COUNT, samples.size)
                    }
                }
            }
            target.send(msg)
            // First call includes copying and loading the models.
            return withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            return errorBundle("the engine took too long — try again")
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        } catch (e: Exception) {
            pending.remove(id)
            return errorBundle(e.message ?: "engine call failed")
        } finally {
            audio?.close()
            scheduleIdleRelease()
        }
    }

    private suspend fun connect(): Messenger = bindLock.withLock {
        service?.let { return it }
        val ready = CompletableDeferred<Messenger>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                val messenger = Messenger(binder)
                service = messenger
                ready.complete(messenger)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The :engine process died (crash or the system killed it).
                service = null
                failAll("the engine stopped — try again")
            }

            override fun onBindingDied(name: ComponentName?) {
                release()
            }
        }
        val bound = context.bindService(
            Intent(context, EngineService::class.java), conn, Context.BIND_AUTO_CREATE
        )
        if (!bound) error("could not start the engine service")
        connection = conn
        withTimeout(BIND_TIMEOUT_MS) { ready.await() }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(tier.keepLoadedMs)
            if (pending.isEmpty()) release()
        }
    }

    private fun failAll(reason: String) {
        val error = errorBundle(reason)
        pending.values.forEach { it.complete(error) }
        pending.clear()
    }

    private fun errorBundle(reason: String) = Bundle().apply { putString(P.KEY_ERROR, reason) }

    private fun toSharedMemory(samples: FloatArray): SharedMemory {
        val shm = SharedMemory.create("command-audio", samples.size * Float.SIZE_BYTES)
        val buffer = shm.mapReadWrite()
        try {
            buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().put(samples)
        } finally {
            SharedMemory.unmap(buffer)
        }
        return shm
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        const val CALL_TIMEOUT_MS = 60_000L
    }
}
