package com.voiceauto.app.engine

/** Message codes and Bundle keys shared by EngineClient (app process) and EngineService (:engine). */
internal object EngineProtocol {
    const val MSG_WARM_UP = 1
    const val MSG_PROCESS = 2

    // Request keys
    const val KEY_SYSTEM = "system"
    const val KEY_TOOLS = "tools"
    const val KEY_KEYWORDS = "keywords"
    const val KEY_TEXT = "text"
    const val KEY_AUDIO = "audio"            // android.os.SharedMemory with float32 samples
    const val KEY_SAMPLE_COUNT = "samples"
    const val KEY_MAX_NEW_TOKENS = "maxNewTokens"

    // Reply keys
    const val KEY_TRANSCRIPT = "transcript"  // plain text heard by Whistle
    const val KEY_RESPONSE = "response"      // Needle's JSON envelope
    const val KEY_ERROR = "error"
    const val KEY_PREFIX_TOKENS = "prefixTokens"
}
