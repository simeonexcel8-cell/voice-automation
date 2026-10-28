package com.voiceauto.app.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Push-to-talk recorder: 16 kHz mono, stops on its own after a pause.
 *
 * End-of-speech uses a simple energy detector calibrated on the first 300 ms of
 * room noise. It is good enough to start; swap in WebRTC VAD (planned in the
 * architecture doc) if noisy rooms cut commands short.
 */
class Recorder {

    @Volatile private var stopRequested = false

    /** Stops the current recording early (the user tapped the mic again). */
    fun stop() {
        stopRequested = true
    }

    /**
     * Records one command. [onLevel] receives 0..1 for the UI meter.
     * Returns the samples, or null if nobody spoke.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(onLevel: (Float) -> Unit): FloatArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            max(minBuffer, FRAME * 8 * Short.SIZE_BYTES),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone is busy or unavailable" }

        // One preallocated buffer for the longest allowed command (about 480 KB).
        val pcm = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        val frame = ShortArray(FRAME)
        var written = 0
        var noiseFloor = 0.0
        var calibrated = 0
        var speechFrames = 0
        var silenceFrames = 0

        try {
            record.startRecording()
            while (written + FRAME <= pcm.size && !stopRequested) {
                ensureActive()
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) continue
                frame.copyInto(pcm, written, 0, n)
                written += n

                val db = dbfs(frame, n)
                onLevel(((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat())

                if (calibrated < CALIBRATION_FRAMES) {
                    noiseFloor += db / CALIBRATION_FRAMES
                    calibrated++
                    continue
                }
                val isSpeech = db > max(noiseFloor + SPEECH_MARGIN_DB, SPEECH_MIN_DB)
                if (isSpeech) {
                    speechFrames++
                    silenceFrames = 0
                } else if (speechFrames > 0) {
                    silenceFrames++
                }
                if (speechFrames >= MIN_SPEECH_FRAMES && silenceFrames >= END_SILENCE_FRAMES) break
                if (speechFrames == 0 && written >= SAMPLE_RATE * NO_SPEECH_SECONDS) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            onLevel(0f)
        }

        if (speechFrames < MIN_SPEECH_FRAMES) return@withContext null
        FloatArray(written) { pcm[it] / 32768f }
    }

    private fun dbfs(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = frame[i] / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / n)
        return if (rms <= 1e-9) -100.0 else 20 * log10(rms)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val FRAME = 480                 // 30 ms
        private const val MAX_SECONDS = 15            // hard stop
        private const val NO_SPEECH_SECONDS = 5       // give up if nobody talks
        private const val CALIBRATION_FRAMES = 10     // 300 ms of room noise
        private const val MIN_SPEECH_FRAMES = 6       // 180 ms of voice
        private const val END_SILENCE_FRAMES = 24     // 720 ms pause ends the command
        private const val SPEECH_MARGIN_DB = 10.0
        private const val SPEECH_MIN_DB = -45.0
    }
}
