package com.voiceauto.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    state: UiState,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUndo: (HistoryItem) -> Unit,
    onSpeakReplies: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Header(state, onSpeakReplies)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.modelsBundled) item { ModelsMissingCard() }
                item { NowCard(state) }
                state.confirm?.let { confirm ->
                    item { ConfirmCard(confirm.why, state.confirmLines, confirm.reasoning, confirm.destructive, onConfirm, onCancel) }
                }
                if (state.history.isNotEmpty()) {
                    item {
                        Text(
                            "History",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(state.history, key = { it.id }) { HistoryRow(it, onUndo) }
                }
            }

            BottomBar(state, onMic, onSend)
        }
    }
}

@Composable
private fun Header(state: UiState, onSpeakReplies: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Voice Automation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Everything stays on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Speak replies", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        Switch(checked = state.speakReplies, onCheckedChange = onSpeakReplies)
    }
}

@Composable
private fun ModelsMissingCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Models not bundled", fontWeight = FontWeight.SemiBold)
            Text(
                "Put needle3.cact (and whistle.cact for voice) in app/src/main/assets/models/, " +
                    "then rebuild. See README.md, step 3.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NowCard(state: UiState) {
    val status = when (state.phase) {
        Phase.Listening -> "Listening… pause when you're done"
        Phase.Thinking -> "Working it out…"
        Phase.Idle -> if (state.heard == null && state.message == null) "Tap the mic and say a command" else null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.heard?.let {
                Text("Heard", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("“$it”", style = MaterialTheme.typography.titleMedium)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (state.phase == Phase.Idle && state.heard == null && state.message == null) {
                Text(
                    "Try: “open WhatsApp”, “off the torch”, “set alarm for 6:30”, “note buy garri and pepper”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    why: String,
    lines: List<String>,
    reasoning: String?,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val container = if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(why, fontWeight = FontWeight.SemiBold)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            reasoning?.let {
                Text("How I read it: $it", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                // Cancel is the prominent button; confirming takes a deliberate tap.
                Button(onClick = onCancel) { Text("Cancel") }
                OutlinedButton(
                    onClick = onConfirm,
                    colors = if (destructive) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) { Text(if (destructive) "Yes, delete" else "Confirm") }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onUndo: (HistoryItem) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                if (item.heard.isNotBlank()) {
                    Text(
                        "“${item.heard}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(item.outcome, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.canUndo) TextButton(onClick = { onUndo(item) }) { Text("Undo") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BottomBar(state: UiState, onMic: () -> Unit, onSend: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val busy = state.phase != Phase.Idle
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MicButton(state, onMic)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            enabled = !busy && state.modelsBundled,
            singleLine = true,
            placeholder = { Text("Or type a command") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSend(typed)
                typed = ""
            }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MicButton(state: UiState, onMic: () -> Unit) {
    val listening = state.phase == Phase.Listening
    val ring by animateFloatAsState(targetValue = if (listening) 1f + state.level * 0.35f else 1f, label = "mic-level")
    val color = when (state.phase) {
        Phase.Listening -> MaterialTheme.colorScheme.error
        Phase.Thinking -> MaterialTheme.colorScheme.surfaceVariant
        Phase.Idle -> MaterialTheme.colorScheme.primary
    }
    val label = when (state.phase) {
        Phase.Listening -> "Stop"
        Phase.Thinking -> "…"
        Phase.Idle -> "Talk"
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
        Box(
            Modifier
                .size(88.dp)
                .scale(ring)
                .border(3.dp, color.copy(alpha = 0.35f), CircleShape),
        )
        Button(
            onClick = onMic,
            enabled = state.phase != Phase.Thinking && state.modelsBundled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = color),
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(color, CircleShape)
                .semantics { contentDescription = if (listening) "Stop listening" else "Start listening" },
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
