package com.nachogoro.smartchess.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.ble.BoardConnectionState
import scp.protocol.GameMode

/**
 * Main menu.
 *
 * The play options are derived from [availableModes], which the ViewModel builds from the adapters
 * actually compiled into the build - a build with no engine adapter simply does not offer engine
 * play, rather than offering it and failing.
 */
@Composable
fun MainMenuScreen(
    availableModes: List<GameMode>,
    connectionState: BoardConnectionState,
    onSelectMode: (GameMode) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onReconnect: () -> Unit,
) {
    // Identified, not Ready: the link is down between moves by design, and the next board read
    // reconnects. Gating play on a live link would disable the menu every 30 idle seconds.
    val boardReady = connectionState is BoardConnectionState.Identified

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Smart Chess", style = MaterialTheme.typography.headlineLarge)

        Spacer(Modifier.height(8.dp))
        BoardStatusChip(connectionState, onReconnect)
        Spacer(Modifier.height(32.dp))

        // Playing requires the physical board, since moves are read from it.
        for (mode in availableModes) {
            Button(
                onClick = { onSelectMode(mode) },
                enabled = boardReady,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(mode.menuLabel(), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!boardReady) {
            Text(
                "Connect your board to start a game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))

        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Games history")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Settings")
        }
    }
}

@Composable
private fun BoardStatusChip(state: BoardConnectionState, onReconnect: () -> Unit) {
    // The link flaps every 30s while idle. Reporting that as connect/disconnect churn would be
    // noise, so the chip tracks whether a board is paired at all.
    val connected = state is BoardConnectionState.Identified
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (connected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = when (state) {
                is BoardConnectionState.Identified -> state.deviceName ?: "Board connected"
                is BoardConnectionState.Failed -> "Board disconnected"
                else -> "No board"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!connected) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

private fun GameMode.menuLabel(): String = when (this) {
    GameMode.LOCAL -> "Play a local player"
    GameMode.ENGINE -> "Play an engine"
    GameMode.REMOTE -> "Play remotely"
}
