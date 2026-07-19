package com.nachogoro.smartchess.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.ble.BlePermissions
import com.nachogoro.smartchess.ble.BoardConnectionState

/**
 * Entry gate: gets the phone talking to the board, walking the user through whatever is missing.
 *
 * Connecting needs three separate things to be true, and each fails differently, so they are
 * checked and surfaced one at a time: runtime permissions, the Bluetooth radio, and (below
 * Android 12 only) the system location toggle.
 */
@Composable
fun ConnectionScreen(
    state: BoardConnectionState,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onContinueWithoutBoard: () -> Unit,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current

    var permissionsGranted by remember { mutableStateOf(BlePermissions.allGranted(context)) }
    var bluetoothEnabled by remember { mutableStateOf(BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) }
    var locationEnabled by remember { mutableStateOf(BlePermissions.locationServicesEnabled(context)) }

    fun refreshRequirements() {
        permissionsGranted = BlePermissions.allGranted(context)
        bluetoothEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        locationEnabled = BlePermissions.locationServicesEnabled(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshRequirements() }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshRequirements() }

    // Navigate onwards as soon as the board is usable.
    LaunchedEffect(state) {
        if (state is BoardConnectionState.Ready) onConnected()
    }

    val ready = permissionsGranted && bluetoothEnabled && locationEnabled

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            state is BoardConnectionState.Ready -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            ready -> Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            else -> Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Connect your board",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        val message = when {
            isConnecting || state is BoardConnectionState.Scanning ->
                "Looking for your board. Make sure it is switched on and close by."

            state is BoardConnectionState.Connecting ->
                "Connecting to ${state.deviceName ?: "the board"}..."

            state is BoardConnectionState.Ready ->
                "Connected to ${state.deviceName ?: "the board"}."

            !permissionsGranted ->
                "Smart Chess needs Bluetooth permission to find your board. It never uses your location."

            !bluetoothEnabled ->
                "Bluetooth is switched off. Turn it on to reach the board."

            !locationEnabled ->
                "On this version of Android, location services must be on for Bluetooth scanning to " +
                    "return results. Smart Chess does not read your location."

            state is BoardConnectionState.Failed -> state.reason

            else -> "Ready to search for your board."
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        if (isConnecting || state is BoardConnectionState.Scanning || state is BoardConnectionState.Connecting) {
            CircularProgressIndicator()
        } else {
            when {
                !permissionsGranted -> Button(
                    onClick = { permissionLauncher.launch(BlePermissions.required.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant Bluetooth permission") }

                !bluetoothEnabled -> Button(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Turn on Bluetooth") }

                !locationEnabled -> Button(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open location settings") }

                else -> Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state is BoardConnectionState.Failed) "Try again" else "Find my board")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Past games are readable without hardware, so never make the board a hard gate.
        TextButton(onClick = onContinueWithoutBoard, modifier = Modifier.fillMaxWidth()) {
            Text("Continue without a board")
        }
    }
}
