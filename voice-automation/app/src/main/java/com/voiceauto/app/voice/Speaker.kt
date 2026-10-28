package com.voiceauto.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Optional spoken replies through Android's own text-to-speech.
 * Speaks only with an offline voice; if none is installed, it stays silent
 * and the app shows text only.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)

    @Volatile var available = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val offline = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
        val voice = offline.firstOrNull { it.locale.country == "NG" }
            ?: offline.firstOrNull { it.locale == Locale.UK }
            ?: offline.firstOrNull()
        if (voice != null) {
            tts.voice = voice
            available = true
        }
    }

    fun say(text: String) {
        if (available) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun shutdown() = tts.shutdown()
}
