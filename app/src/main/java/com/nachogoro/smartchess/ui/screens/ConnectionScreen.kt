package com.nachogoro.smartchess.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nachogoro.smartchess.R
import com.nachogoro.smartchess.ble.BlePermissions
import com.nachogoro.smartchess.ble.BoardConnectionFailure
import com.nachogoro.smartchess.ble.BoardConnectionState

/**
 * Entry gate: gets the phone talking to the board, walking the user through whatever is missing.
 *
 * Connecting needs three separate things to be true, and each fails differently, so they are
 * checked and surfaced one at a time: runtime permissions, the Bluetooth radio, and (below
 * Android 12 only) the system location toggle.
 *
 * A fourth requirement is the board's, not Android's: its firmware only serves an encrypted link,
 * so the phone has to be paired with it. That step is the user's to complete in a system dialog,
 * which is why it is narrated here rather than folded into the progress spinner.
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

    // The radio is toggled from quick settings as often as from the button here, and the system
    // dialog reports back while the adapter is still turning on, so the launcher's result alone
    // would leave this screen believing Bluetooth is off after it came up.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshRequirements()
        }
        // Exported, because the broadcast comes from the system rather than from this app, and
        // Android 13 does not deliver system broadcasts to a receiver registered as not exported.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Navigate onwards as soon as the board is usable. An idle link still counts: the board is
    // identified, and the first read of the game reconnects it.
    LaunchedEffect(state) {
        if (state is BoardConnectionState.Identified) onConnected()
    }

    val ready = permissionsGranted && bluetoothEnabled && locationEnabled

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            state is BoardConnectionState.Identified -> Icon(
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
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        val theBoard = stringResource(R.string.connect_the_board)

        // The reported state is checked before isConnecting, which is true for the whole attempt
        // and would otherwise describe every step of it as "searching" - including pairing, where
        // the user is being waited on and needs to be told so.
        val message = when {
            state is BoardConnectionState.Pairing ->
                stringResource(R.string.connect_pairing, state.deviceName ?: theBoard)

            state is BoardConnectionState.Connecting ->
                stringResource(R.string.connect_connecting, state.deviceName ?: theBoard)

            state is BoardConnectionState.Identified ->
                stringResource(R.string.connect_connected, state.deviceName ?: theBoard)

            isConnecting || state is BoardConnectionState.Scanning ->
                stringResource(R.string.connect_searching)

            !permissionsGranted ->
                stringResource(R.string.connect_needs_permission)

            !bluetoothEnabled ->
                stringResource(R.string.connect_bluetooth_off)

            !locationEnabled ->
                stringResource(R.string.connect_needs_location)

            // The connection layer reports a code for what only the user can fix, and its own
            // message for everything else, which is diagnostic rather than advice.
            state is BoardConnectionState.Failed -> when (state.kind) {
                // Only reached once the radio is back on, since the check above wins while it is
                // off: the attempt failed for a reason that no longer holds, so it is not a failure
                // to report.
                BoardConnectionFailure.BLUETOOTH_OFF ->
                    stringResource(R.string.connect_ready)

                BoardConnectionFailure.NO_BOARD_FOUND ->
                    stringResource(R.string.connect_no_board_found)

                BoardConnectionFailure.PAIRING_REFUSED ->
                    stringResource(R.string.connect_pairing_refused)

                BoardConnectionFailure.PAIRING_TIMED_OUT ->
                    stringResource(R.string.connect_pairing_timed_out)

                BoardConnectionFailure.STALE_BOND ->
                    stringResource(R.string.connect_stale_bond)

                BoardConnectionFailure.GENERIC -> state.reason
            }

            else -> stringResource(R.string.connect_ready)
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        val inProgress = isConnecting ||
            state is BoardConnectionState.Scanning ||
            state is BoardConnectionState.Pairing ||
            state is BoardConnectionState.Connecting

        if (inProgress) {
            CircularProgressIndicator()
        } else {
            when {
                !permissionsGranted -> Button(
                    onClick = { permissionLauncher.launch(BlePermissions.required.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.connect_grant_permission)) }

                !bluetoothEnabled -> Button(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.connect_turn_on_bluetooth)) }

                !locationEnabled -> Button(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.connect_open_location_settings)) }

                else -> {
                    // A pairing the board has stopped accepting can only be deleted from system
                    // settings - there is no public API for it - so retrying alone would loop.
                    if (state is BoardConnectionState.Failed &&
                        state.kind == BoardConnectionFailure.STALE_BOND
                    ) {
                        Button(
                            onClick = {
                                enableBluetoothLauncher.launch(
                                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.connect_forget_board)) }

                        Spacer(Modifier.height(8.dp))
                    }

                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // A failure for the radio being off is not one to retry: Bluetooth is on
                        // by the time this button shows, so this is a first attempt again.
                        val retrying = state is BoardConnectionState.Failed &&
                            state.kind != BoardConnectionFailure.BLUETOOTH_OFF
                        Text(
                            if (retrying) stringResource(R.string.connect_try_again)
                            else stringResource(R.string.connect_find_board)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Past games are readable without hardware, so never make the board a hard gate.
        TextButton(onClick = onContinueWithoutBoard, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_continue_without))
        }
    }
}
