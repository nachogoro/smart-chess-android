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
import com.nachogoro.smartchess.ui.components.ParameterFormFields
import com.nachogoro.smartchess.ui.components.rememberParameterFormState
import scp.protocol.Color
import scp.protocol.ConfigureEngineGameParams
import scp.protocol.ConfigureRemoteGameParams
import scp.protocol.EngineGameConfig
import scp.protocol.EngineInfo
import scp.protocol.GameMode
import scp.protocol.LocalGameConfig
import scp.protocol.PlatformInfo
import scp.protocol.RemoteGameConfig
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
    onSaveModuleSettings: (String, Map<String, String>) -> Unit,
    onCancelSeek: () -> Unit,
) {
    // The mode was already chosen in the main menu, so answer that question automatically.
    val pendingMode = uiModule.pendingGameModeSelection
    LaunchedEffect(pendingMode) {
        pendingMode?.complete(mode)
    }

    // Note the orchestrator only asks this when more than one engine is registered; with a single
    // engine it picks that one itself and never prompts. So this branch is genuinely multi-engine.
    val pendingEngine = uiModule.pendingEngineSelection

    // Game start arrives as a notification rather than as a return value.
    val started = uiModule.lastGameStartedInfo
    LaunchedEffect(started) {
        if (started != null) onGameStarted()
    }

    val pendingLocal = uiModule.pendingLocalGameConfig
    val pendingEngineConfig = uiModule.pendingEngineGameConfig
    val pendingPlatform = uiModule.pendingRemotePlatformSelection
    val pendingRemoteConfig = uiModule.pendingRemoteGameConfig

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

            pendingEngineConfig != null -> EngineGameForm(
                params = pendingEngineConfig.input,
                savedValues = settings.settingsFor(pendingEngineConfig.input.engineName),
                onSubmit = { config, rawValues ->
                    // Persist before completing: completing unparks the orchestrator, which
                    // clears the pending request and recomposes this branch away.
                    onSaveModuleSettings(pendingEngineConfig.input.engineName, rawValues)
                    pendingEngineConfig.complete(config)
                },
                onCancel = {
                    pendingEngineConfig.cancel()
                    onBack()
                },
            )

            pendingEngine != null -> EnginePicker(
                engines = pendingEngine.input,
                onSelect = { pendingEngine.complete(it.name) },
                onCancel = {
                    pendingEngine.cancel()
                    onBack()
                },
            )

            pendingRemoteConfig != null -> RemoteGameForm(
                params = pendingRemoteConfig.input,
                savedPlatformValues = settings.settingsFor(pendingRemoteConfig.input.platform),
                savedSeekValues = settings.settingsFor(seekSettingsKey(pendingRemoteConfig.input.platform)),
                onSubmit = { config, platformValues, seekValues ->
                    val platform = pendingRemoteConfig.input.platform
                    onSaveModuleSettings(platform, platformValues)
                    onSaveModuleSettings(seekSettingsKey(platform), seekValues)
                    pendingRemoteConfig.complete(config)
                },
                onCancel = {
                    pendingRemoteConfig.cancel()
                    onBack()
                },
            )

            pendingPlatform != null -> PlatformPicker(
                platforms = pendingPlatform.input,
                onSelect = { pendingPlatform.complete(it.platform) },
                onCancel = {
                    pendingPlatform.cancel()
                    onBack()
                },
            )

            // Seeking is not a pending request: the orchestrator has finished setup and is waiting
            // on the platform. Backing out therefore has to withdraw the seek explicitly rather
            // than cancel a prompt, or the seek stays live on the server after the user has left.
            uiModule.isSeeking -> WaitingForOrchestrator(
                message = "Looking for an opponent...",
                onCancel = onCancelSeek,
            )

            else -> WaitingForOrchestrator(
                message = "Preparing game...",
                onCancel = onBack,
            )
        }
    }
}

/**
 * Settings key for a platform's seek defaults.
 *
 * Kept separate from the platform's own settings because the two have different lifetimes: the API
 * token is entered once and kept, while a seek is a per-game choice that only defaults to last
 * time's. Separate keys also avoid one set's parameter names colliding with the other's.
 */
private fun seekSettingsKey(platform: String): String = "$platform seek"

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
private fun EnginePicker(
    engines: List<EngineInfo>,
    onSelect: (EngineInfo) -> Unit,
    onCancel: () -> Unit,
) {
    Text("Choose an engine", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(20.dp))

    for (engine in engines) {
        OutlinedButton(
            onClick = { onSelect(engine) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(engine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    engine.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

/**
 * Setup for a game against an engine.
 *
 * The engine's own options are not hardcoded here: the adapter declares them as
 * [scp.protocol.ParameterDescriptor]s and [ParameterFormFields] renders them, so an engine added
 * later needs no changes to this screen. Previously entered values are pre-filled from settings.
 */
@Composable
private fun EngineGameForm(
    params: ConfigureEngineGameParams,
    savedValues: Map<String, String>,
    onSubmit: (EngineGameConfig, Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    var playerColor by remember { mutableStateOf(Color.WHITE) }
    val timeControl = rememberTimeControlState()
    val parameterForm = rememberParameterFormState(params.parameters, savedValues)
    var validationError by remember { mutableStateOf<String?>(null) }

    Text("Play ${params.engineName}", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "The engine runs on this phone. Move its pieces on the board when it replies.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    // The orchestrator re-prompts with an error when the adapter rejects a configuration, so both
    // that and local validation surface in the same place.
    SetupErrorBanner(params.errorMessage ?: validationError)

    Text("Play as", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        ColorChoice(
            label = "White",
            selected = playerColor == Color.WHITE,
            onClick = { playerColor = Color.WHITE },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        ColorChoice(
            label = "Black",
            selected = playerColor == Color.BLACK,
            onClick = { playerColor = Color.BLACK },
            modifier = Modifier.weight(1f),
        )
    }

    if (params.parameters.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Text("Engine options", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ParameterFormFields(parameterForm)
    }

    Spacer(Modifier.height(8.dp))
    TimeControlSection(timeControl)
    Spacer(Modifier.height(28.dp))

    Row(Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val error = parameterForm.validationError()
                validationError = error
                if (error != null) return@Button
                onSubmit(
                    EngineGameConfig(
                        playerColor = playerColor,
                        engineSettings = parameterForm.toTypedValues(),
                        timeControl = timeControl.toTimeControl(),
                    ),
                    parameterForm.toStringValues(),
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

/**
 * Setup for a game against a remote opponent.
 *
 * Two independent parameter sets arrive from the adapter: connection settings (a Lichess API token
 * and the like) and seek settings (time control, rated or casual, which colour). They are rendered
 * and persisted separately because they have different lifetimes - the token is entered once and
 * kept, while the seek is a per-game choice that merely defaults to last time's.
 */
@Composable
private fun RemoteGameForm(
    params: ConfigureRemoteGameParams,
    savedPlatformValues: Map<String, String>,
    savedSeekValues: Map<String, String>,
    onSubmit: (RemoteGameConfig, Map<String, String>, Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val platformForm = rememberParameterFormState(params.parameters, savedPlatformValues)
    val seekForm = rememberParameterFormState(params.seekParameters, savedSeekValues)
    var validationError by remember { mutableStateOf<String?>(null) }

    Text("Play on ${params.platform}", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Your opponent plays online. Move their pieces on your board as they arrive.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    SetupErrorBanner(params.errorMessage ?: validationError)

    if (params.parameters.isNotEmpty()) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ParameterFormFields(platformForm)
        Spacer(Modifier.height(12.dp))
    }

    if (params.seekParameters.isNotEmpty()) {
        Text("Game", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ParameterFormFields(seekForm)
    }

    Spacer(Modifier.height(20.dp))

    Row(Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val error = platformForm.validationError() ?: seekForm.validationError()
                validationError = error
                if (error != null) return@Button
                onSubmit(
                    RemoteGameConfig(
                        platformSettings = platformForm.toTypedValues(),
                        seekSettings = seekForm.toTypedValues(),
                    ),
                    platformForm.toStringValues(),
                    seekForm.toStringValues(),
                )
            },
            modifier = Modifier.weight(1f).height(50.dp),
        ) { Text("Find a game") }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(50.dp),
        ) { Text("Back") }
    }
}

@Composable
private fun PlatformPicker(
    platforms: List<PlatformInfo>,
    onSelect: (PlatformInfo) -> Unit,
    onCancel: () -> Unit,
) {
    Text("Choose a platform", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(20.dp))

    for (platform in platforms) {
        OutlinedButton(
            onClick = { onSelect(platform) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(platform.platform, style = MaterialTheme.typography.titleMedium)
                if (platform.features.isNotEmpty()) {
                    Text(
                        platform.features.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ColorChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp)) { Text(label) }
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
