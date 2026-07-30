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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.ble.BoardConnectionState
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
    onSavePlayerName: (String) -> Unit,
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
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Text("Your nickname", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Used as the default player name when you start a game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text("Nickname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Text("Board", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                when (connectionState) {
                    is BoardConnectionState.Identified -> {
                        val caps = connectionState.capabilities
                        Text(connectionState.deviceName ?: "Connected board")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Protocol v${caps.protocolVersion} - ${caps.rows}x${caps.columns} - " +
                                "detects ${caps.capabilityLevelLabel()}" +
                                if (caps.simulationMode) " - simulation mode" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        "No board connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settings.moduleSettings.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Saved module settings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Values you entered when setting up a game, remembered so you do not have to " +
                    "type them again.",
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
                            Text("Forget")
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
            ) { Text("Save") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Cancel") }
        }
    }
}

private fun com.nachogoro.smartchess.ble.BoardCapabilitiesPacket.capabilityLevelLabel(): String =
    when (capabilityLevel) {
        1 -> "occupancy"
        2 -> "piece colour"
        else -> "full piece type"
    }
