package com.voiceauto.app.engine

/**
 * Thin JNI wrapper over the Cactus engine (see src/main/cpp/needle_jni.c).
 *
 * Only EngineService may call this, and only from its single engine thread:
 * the engine holds one global model and conversation per process and is not
 * thread-safe. Every function throws RuntimeException on an engine error.
 */
internal object NeedleNative {
    init {
        System.loadLibrary("needle_bridge")
    }

    /** Loads a .cact file (Needle or Whistle). Both can be loaded into one process. */
    @JvmStatic external fun nativeLoad(path: String): Int

    /** Sets system facts and tool schemas. Returns the static prefix length in tokens. */
    @JvmStatic external fun nativeInit(system: String, toolsJson: String): Int

    /** Whistle speech-to-text. 16 kHz mono floats in [-1, 1]. Returns UTF-8 JSON. */
    @JvmStatic external fun nativeTranscribe(samples: FloatArray, language: String?, keywords: String?): ByteArray

    /** Needle tool calling. Returns the engine's UTF-8 JSON envelope. */
    @JvmStatic external fun nativeComplete(text: String, maxNewTokens: Int): ByteArray

    /** Clears the conversation, so every command starts fresh. */
    @JvmStatic external fun nativeReset()
}
