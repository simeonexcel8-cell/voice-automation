package com.voiceauto.app.ui

import android.Manifest
import android.app.Application
import android.os.BatteryManager
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceauto.app.App
import com.voiceauto.app.engine.EngineRequest
import com.voiceauto.app.engine.ModelFiles
import com.voiceauto.app.engine.NeedleResponse
import com.voiceauto.app.engine.ToolCall
import com.voiceauto.app.policy.Decision
import com.voiceauto.app.tools.ToolResult
import com.voiceauto.app.voice.Recorder
import com.voiceauto.app.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { Idle, Listening, Thinking }

data class HistoryItem(
    val id: Long,
    val heard: String,
    val outcome: String,
    val canUndo: Boolean,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val level: Float = 0f,
    val heard: String? = null,
    val message: String? = null,
    val confirm: Decision.Confirm? = null,
    val confirmLines: List<String> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val modelsBundled: Boolean = true,
    val speakReplies: Boolean = false,
    val speechAvailable: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val recorder = Recorder()
    private val speaker = Speaker(application)
    private val undoActions = mutableMapOf<Long, suspend () -> String>()
    private var commandJob: Job? = null

    private val _state = MutableStateFlow(UiState(modelsBundled = ModelFiles.bundled(application)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Load the models in the background as soon as the screen opens.
        if (_state.value.modelsBundled) {
            viewModelScope.launch { app.engineInUse().warmUp(request()) }
        }
    }

    /** Mic button: start listening, or stop early if already listening. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onMicTapped() {
        when (_state.value.phase) {
            Phase.Listening -> recorder.stop()
            Phase.Thinking -> Unit
            Phase.Idle -> commandJob = viewModelScope.launch {
                _state.update { it.copy(phase = Phase.Listening, message = null, heard = null, confirm = null) }
                val audio = try {
                    recorder.record { level -> _state.update { it.copy(level = level) } }
                } catch (e: IllegalStateException) {
                    finish(e.message ?: "The microphone isn't available.")
                    return@launch
                }
                if (audio == null) {
                    finish("I didn't hear anything.")
                    return@launch
                }
                run(request(audio = audio))
            }
        }
    }

    /** Typed commands go through exactly the same path, minus speech-to-text. */
    fun onTextSubmitted(text: String) {
        if (text.isBlank() || _state.value.phase != Phase.Idle) return
        commandJob = viewModelScope.launch {
            _state.update { it.copy(message = null, heard = text.trim(), confirm = null) }
            run(request(text = text.trim()))
        }
    }

    fun onConfirm() {
        val pending = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, confirmLines = emptyList()) }
        viewModelScope.launch { execute(pending.calls) }
    }

    fun onCancel() {
        _state.update { it.copy(confirm = null, confirmLines = emptyList(), message = "Cancelled.") }
        record("Cancelled", canUndoId = null)
    }

    fun onUndo(item: HistoryItem) {
        val undo = undoActions.remove(item.id) ?: return
        viewModelScope.launch {
            val message = runCatching { undo() }.getOrElse { "Couldn't undo that." }
            _state.update { s ->
                s.copy(
                    message = message,
                    history = s.history.map { if (it.id == item.id) it.copy(canUndo = false) else it },
                )
            }
        }
    }

    /** The user refused the microphone: say so once, typed commands still work. */
    fun onTextOnlyMode() {
        _state.update { it.copy(message = "No microphone access — type your command below instead.") }
    }

    fun onSpeakRepliesChanged(on: Boolean) {
        _state.update { it.copy(speakReplies = on, speechAvailable = speaker.available) }
    }

    private suspend fun run(request: EngineRequest) {
        _state.update { it.copy(phase = Phase.Thinking, level = 0f) }
        val result = app.engineInUse().process(request)
        result.transcript?.let { heard -> _state.update { it.copy(heard = heard) } }

        if (result.error != null) return finish(result.error)
        if (result.transcript.isNullOrBlank()) return finish("I didn't catch that.")
        val raw = result.response ?: return finish("No answer from the engine.")
        val response = runCatching { NeedleResponse.parse(raw) }.getOrElse { return finish("The engine's answer was unreadable.") }

        when (val decision = app.policy.decide(response)) {
            is Decision.Refuse -> finish(decision.message)
            is Decision.Run -> execute(decision.calls)
            is Decision.Confirm -> _state.update {
                it.copy(
                    phase = Phase.Idle,
                    confirm = decision,
                    confirmLines = decision.calls.map { call -> app.registry.find(call.name)!!.describe(call.arguments) },
                )
            }
        }
    }

    private suspend fun execute(calls: List<ToolCall>) {
        val messages = mutableListOf<String>()
        for (call in calls) {
            val tool = app.registry.find(call.name) ?: continue
            when (val result = tool.execute(getApplication(), call.arguments)) {
                is ToolResult.Done -> {
                    val id = System.nanoTime()
                    result.undo?.let { undoActions[id] = it }
                    record(result.message, canUndoId = id.takeIf { result.undo != null })
                    messages += result.message
                }
                is ToolResult.Failed -> {
                    record(result.reason, canUndoId = null)
                    messages += result.reason
                }
            }
        }
        finish(messages.joinToString(". "), recordIt = false)
    }

    private fun finish(message: String, recordIt: Boolean = true) {
        if (recordIt) record(message, canUndoId = null)
        _state.update { it.copy(phase = Phase.Idle, level = 0f, message = message) }
        if (_state.value.speakReplies) speaker.say(message)
    }

    private fun record(outcome: String, canUndoId: Long?) {
        val item = HistoryItem(
            id = canUndoId ?: System.nanoTime(),
            heard = _state.value.heard.orEmpty(),
            outcome = outcome,
            canUndo = canUndoId != null,
        )
        _state.update { it.copy(history = (listOf(item) + it.history).take(MAX_HISTORY)) }
    }

    private fun request(text: String? = null, audio: FloatArray? = null) = EngineRequest(
        system = systemFacts(),
        toolsJson = app.registry.toolsJson(),
        keywords = app.installedApps.keywords(),
        text = text,
        audio = audio,
    )

    /** Facts, not instructions: Needle resolves "tomorrow at 7" against the date. */
    private fun systemFacts(): String {
        val date = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date())
        val battery = getApplication<Application>().getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "date: $date; locale: en-NG; device: android phone; battery: $battery%"
    }

    override fun onCleared() {
        recorder.stop()
        speaker.shutdown()
    }

    private companion object { const val MAX_HISTORY = 50 }
}
