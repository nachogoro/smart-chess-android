package com.nachogoro.smartchess.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.data.AppSettings
import com.nachogoro.smartchess.ui.AndroidUIModule
import scp.protocol.GameMode
import scp.protocol.LocalGameConfig
import scp.protocol.TimeControl
import scp.protocol.TimeControlType

/**
 * Drives the orchestrator's setup flow.
 *
 * The orchestrator asks the questions, one suspended call at a time; this screen renders whichever
 * question is currently outstanding and answers it. Backing out cancels every pending request so
 * the orchestrator unwinds instead of staying parked forever.
 */
@Composable
fun GameSetupScreen(
    mode: GameMode,
    uiModule: AndroidUIModule,
    settings: AppSettings,
    onBack: () -> Unit,
    onGameStarted: () -> Unit,
) {
    // The mode was already chosen in the main menu, so answer that question automatically.
    val pendingMode = uiModule.pendingGameModeSelection
    LaunchedEffect(pendingMode) {
        pendingMode?.complete(mode)
    }

    // Game start arrives as a notification rather than as a return value.
    val started = uiModule.lastGameStartedInfo
    LaunchedEffect(started) {
        if (started != null) onGameStarted()
    }

    val pendingLocal = uiModule.pendingLocalGameConfig

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        when {
            pendingLocal != null -> LocalGameForm(
                errorMessage = pendingLocal.input,
                settings = settings,
                onSubmit = { pendingLocal.complete(it) },
                onCancel = {
                    pendingLocal.cancel()
                    onBack()
                },
            )

            else -> WaitingForOrchestrator(
                message = if (uiModule.isSeeking) "Looking for an opponent..." else "Preparing game...",
                onCancel = onBack,
            )
        }
    }
}

@Composable
private fun WaitingForOrchestrator(message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun LocalGameForm(
    errorMessage: String?,
    settings: AppSettings,
    onSubmit: (LocalGameConfig) -> Unit,
    onCancel: () -> Unit,
) {
    // The saved nickname is the natural default for whoever owns the phone.
    var whiteName by remember { mutableStateOf(settings.playerName.ifBlank { "White" }) }
    var blackName by remember { mutableStateOf("Black") }
    val timeControl = rememberTimeControlState()

    Text("Local game", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Both players share the physical board. Tap the screen when you have finished your move.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    SetupErrorBanner(errorMessage)

    OutlinedTextField(
        value = whiteName,
        onValueChange = { whiteName = it },
        label = { Text("White player") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = blackName,
        onValueChange = { blackName = it },
        label = { Text("Black player") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(20.dp))
    TimeControlSection(timeControl)
    Spacer(Modifier.height(28.dp))

    Row(Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                onSubmit(
                    LocalGameConfig(
                        whiteName = whiteName.trim().ifBlank { "White" },
                        blackName = blackName.trim().ifBlank { "Black" },
                        timeControl = timeControl.toTimeControl(),
                    )
                )
            },
            modifier = Modifier.weight(1f).height(50.dp),
        ) { Text("Start game") }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(50.dp),
        ) { Text("Back") }
    }
}

@Composable
fun SetupErrorBanner(message: String?) {
    if (message.isNullOrBlank()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Spacer(Modifier.height(16.dp))
}

/** Mutable state behind [TimeControlSection]. */
class TimeControlState {
    var enabled by mutableStateOf(false)
    var minutes by mutableStateOf("10")
    var incrementSeconds by mutableStateOf("0")

    fun toTimeControl(): TimeControl? {
        if (!enabled) return null
        val mins = minutes.toIntOrNull() ?: return null
        val inc = incrementSeconds.toIntOrNull() ?: 0
        return TimeControl(
            type = TimeControlType.FISCHER,
            initialMs = mins * 60_000L,
            incrementMs = inc * 1000L,
        )
    }
}

@Composable
fun rememberTimeControlState(): TimeControlState = remember { TimeControlState() }

@Composable
fun TimeControlSection(state: TimeControlState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Use clocks", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fischer increment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.enabled, onCheckedChange = { state.enabled = it })
    }

    if (state.enabled) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.minutes,
                onValueChange = { new -> state.minutes = new.filter { it.isDigit() } },
                label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = state.incrementSeconds,
                onValueChange = { new -> state.incrementSeconds = new.filter { it.isDigit() } },
                label = { Text("Increment (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
