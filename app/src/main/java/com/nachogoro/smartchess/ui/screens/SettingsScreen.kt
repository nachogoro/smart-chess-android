package com.nachogoro.smartchess.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.R
import com.nachogoro.smartchess.ble.BoardConnectionState
import com.nachogoro.smartchess.data.AppLanguage
import com.nachogoro.smartchess.data.AppSettings

/**
 * Settings.
 *
 * Per-engine and per-platform values (API tokens, difficulty) are not edited here: they are
 * captured in each module's own setup form the first time a game is started and remembered from
 * then on. This screen shows what has been remembered and lets the user clear it.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    connectionState: BoardConnectionState,
    language: AppLanguage,
    onSetLanguage: (AppLanguage) -> Unit,
    onSavePlayerName: (String) -> Unit,
    onSetAnnounceOpponentMoves: (Boolean) -> Unit,
    onClearModuleSettings: (String) -> Unit,
    onBack: () -> Unit,
) {
    var playerName by remember(settings.playerName) { mutableStateOf(settings.playerName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_nickname_heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_nickname_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text(stringResource(R.string.settings_nickname_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_language_heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_language_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Applied on selection, which recreates the activity: waiting for Save would mean showing
        // a language that is not yet in force.
        Row(Modifier.selectableGroup()) {
            for (option in AppLanguage.entries) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = option == language,
                            onClick = { onSetLanguage(option) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == language, onClick = null)
                    Spacer(Modifier.width(4.dp))
                    Text(option.label(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Applied on the spot rather than on Save: a switch that has visibly moved but not taken
        // effect is a trap, and the Save button exists for the text field above.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_announce_heading), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_announce_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.announceOpponentMoves,
                onCheckedChange = onSetAnnounceOpponentMoves,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_board_heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                when (connectionState) {
                    is BoardConnectionState.Identified -> {
                        val caps = connectionState.capabilities
                        Text(connectionState.deviceName ?: stringResource(R.string.settings_board_unnamed))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.settings_board_summary,
                                caps.protocolVersion,
                                caps.rows,
                                caps.columns,
                                caps.capabilityLevelLabel(),
                                if (caps.simulationMode) {
                                    stringResource(R.string.settings_board_simulation)
                                } else {
                                    ""
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        stringResource(R.string.settings_board_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settings.moduleSettings.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_modules_heading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_modules_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            for ((moduleName, values) in settings.moduleSettings) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(moduleName, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            values.keys.sorted().joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onClearModuleSettings(moduleName) }) {
                            Text(stringResource(R.string.settings_forget))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Row(
            Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = {
                    onSavePlayerName(playerName.trim())
                    onBack()
                },
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(stringResource(R.string.settings_save)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(stringResource(R.string.settings_cancel)) }
        }
    }
}

@Composable
private fun com.nachogoro.smartchess.ble.BoardCapabilitiesPacket.capabilityLevelLabel(): String =
    stringResource(
        when (capabilityLevel) {
            1 -> R.string.board_detects_occupancy
            2 -> R.string.board_detects_colour
            else -> R.string.board_detects_piece_type
        }
    )

/**
 * The language's own name, in that language.
 *
 * Not translated between languages, deliberately: someone who has the app in a language they
 * cannot read needs to find their own in the list, and "Spanish" would not help them.
 */
private fun AppLanguage.label(): String = when (this) {
    AppLanguage.SYSTEM -> "System"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.SPANISH -> "Español"
}
