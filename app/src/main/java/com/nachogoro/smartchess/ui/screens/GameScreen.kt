package com.nachogoro.smartchess.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nachogoro.smartchess.ui.AndroidUIModule
import com.nachogoro.smartchess.ui.components.ChessBoardView
import com.nachogoro.smartchess.ui.components.STARTING_FEN
import kotlinx.coroutines.delay
import scp.orchestrator.GameSession
import scp.orchestrator.OrchestratorState
import scp.protocol.Clocks
import scp.protocol.Color as ChessColor
import scp.protocol.GameMode
import scp.protocol.PromotionPiece

/**
 * The in-game screen.
 *
 * The physical board is the input device: the player moves the pieces and then taps the screen to
 * say "done", exactly like pressing a chess clock. The app then reads the board over BLE and works
 * out what was played. Consequently this screen shows no interactive board - only clocks, whose
 * turn it is, and an opt-in view of the position for when a player wants to check they are in sync.
 */
@Composable
fun GameScreen(
    session: GameSession?,
    orchestratorState: OrchestratorState,
    uiModule: AndroidUIModule,
    onMoveReady: () -> Unit,
    onResign: () -> Unit,
    onOfferDraw: () -> Unit,
    onAbort: () -> Unit,
    onGameEnd: () -> Unit,
) {
    var showBoard by remember { mutableStateOf(false) }

    val awaitingPlayer = orchestratorState is OrchestratorState.AwaitingPlayerMove
    val turnColor = when (orchestratorState) {
        is OrchestratorState.AwaitingPlayerMove -> orchestratorState.color
        is OrchestratorState.AwaitingOpponentMove -> orchestratorState.color
        else -> null
    }

    GameDialogs(uiModule, onGameEnd)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Black's clock is rotated so it reads correctly from the far side of the board.
            ClockPanel(
                label = session?.blackName ?: "Black",
                timeMs = session?.clocks?.blackMs,
                isActive = turnColor == ChessColor.BLACK,
                rotated = true,
                modifier = Modifier.fillMaxWidth().weight(1f),
                baseClocks = session?.clocks,
                activeColor = turnColor,
                color = ChessColor.BLACK,
            )

            CenterPanel(
                session = session,
                orchestratorState = orchestratorState,
                uiModule = uiModule,
                awaitingPlayer = awaitingPlayer,
                showBoard = showBoard,
                onToggleBoard = { showBoard = !showBoard },
                onTapMoveReady = onMoveReady,
                onResign = onResign,
                onOfferDraw = onOfferDraw,
                onAbort = onAbort,
            )

            ClockPanel(
                label = session?.whiteName ?: "White",
                timeMs = session?.clocks?.whiteMs,
                isActive = turnColor == ChessColor.WHITE,
                rotated = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
                baseClocks = session?.clocks,
                activeColor = turnColor,
                color = ChessColor.WHITE,
            )
        }
    }
}

@Composable
private fun CenterPanel(
    session: GameSession?,
    orchestratorState: OrchestratorState,
    uiModule: AndroidUIModule,
    awaitingPlayer: Boolean,
    showBoard: Boolean,
    onToggleBoard: () -> Unit,
    onTapMoveReady: () -> Unit,
    onResign: () -> Unit,
    onOfferDraw: () -> Unit,
    onAbort: () -> Unit,
) {
    // The whole middle of the screen is the "I have moved" target, so it can be hit without
    // looking. Feedback is suppressed to keep it calm rather than flashing on every stray tap.
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = awaitingPlayer,
                onClick = onTapMoveReady,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val statusText = when (orchestratorState) {
            is OrchestratorState.Idle -> "Waiting..."
            is OrchestratorState.SeekingGame -> "Looking for an opponent..."
            is OrchestratorState.AwaitingPlayerMove ->
                "${orchestratorState.color.displayName()} to move"

            // An engine is computing rather than merely absent, so say so - "waiting for Black"
            // reads like a stall when the phone is in fact busy searching.
            is OrchestratorState.AwaitingOpponentMove ->
                if (session?.mode == GameMode.ENGINE) {
                    "Engine is thinking..."
                } else {
                    "Waiting for ${orchestratorState.color.displayName()}..."
                }
        }

        Text(
            statusText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (awaitingPlayer) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Move on the board, then tap anywhere",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }

        NotificationArea(uiModule)

        if (showBoard) {
            Spacer(Modifier.height(12.dp))
            ChessBoardView(
                fen = uiModule.currentFen ?: STARTING_FEN,
                // Show the position from the perspective of whoever owns the phone.
                flipped = session?.playerColor == ChessColor.BLACK,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Deliberately understated: checking the board is occasionally useful, never the point.
            IconButton(onClick = onToggleBoard) {
                Icon(
                    Icons.Default.GridOn,
                    contentDescription = if (showBoard) "Hide board" else "Show board",
                    tint = if (showBoard) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            TextButton(onClick = onOfferDraw) { Text("Draw") }
            TextButton(onClick = onResign) { Text("Resign") }
            if (session?.mode == GameMode.REMOTE) {
                TextButton(onClick = onAbort) { Text("Abort") }
            }
        }
    }
}

/**
 * One player's clock.
 *
 * The session only carries a snapshot taken at the last move, so the active side is counted down
 * locally from that snapshot. When a remote server is the authority its values simply replace the
 * snapshot on the next move, and the countdown restarts from the corrected figure.
 */
@Composable
private fun ClockPanel(
    label: String,
    timeMs: Long?,
    isActive: Boolean,
    rotated: Boolean,
    modifier: Modifier = Modifier,
    baseClocks: Clocks?,
    activeColor: ChessColor?,
    color: ChessColor,
) {
    var elapsedMs by remember(baseClocks, activeColor) { mutableLongStateOf(0L) }

    LaunchedEffect(baseClocks, activeColor) {
        if (activeColor != color) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(200)
            elapsedMs = System.currentTimeMillis() - startedAt
        }
    }

    val remaining = timeMs?.let { if (isActive) (it - elapsedMs).coerceAtLeast(0) else it }
    val lowOnTime = remaining != null && remaining < 30_000

    val background = when {
        isActive && lowOnTime -> MaterialTheme.colorScheme.errorContainer
        isActive -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(color = background, modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxSize().rotate(if (rotated) 180f else 0f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = remaining?.formatClock() ?: "--:--",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun NotificationArea(uiModule: AndroidUIModule) {
    val opponentMove = uiModule.lastOpponentMove
    val error = uiModule.lastError
    val desync = uiModule.desyncInfo

    if (opponentMove != null) {
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Opponent played ${opponentMove.san}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Play it on your board, make your reply, then tap.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (desync != null) {
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Board out of sync",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    desync.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { uiModule.clearDesync() }) { Text("Dismiss") }
            }
        }
    }

    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { uiModule.clearError() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun GameDialogs(uiModule: AndroidUIModule, onGameEnd: () -> Unit) {
    // Promotion: the board cannot tell a queen from a knight, so the player must say.
    uiModule.pendingPromotion?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Promote to") },
            text = { Text("Which piece did you promote to?") },
            confirmButton = {
                Row {
                    for (piece in PromotionPiece.entries) {
                        TextButton(onClick = { request.complete(piece) }) {
                            Text(piece.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            },
        )
    }

    // Two legal moves can produce the same physical arrangement; only the player knows which.
    uiModule.pendingDisambiguation?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Which move did you play?") },
            text = { Text("The board position matches more than one legal move.") },
            confirmButton = {
                Column {
                    for (move in request.input.moves) {
                        TextButton(onClick = { request.complete(move) }) { Text(move) }
                    }
                }
            },
        )
    }

    uiModule.pendingDrawResponse?.let { request ->
        AlertDialog(
            onDismissRequest = { request.complete(false) },
            title = { Text("Draw offered") },
            text = { Text("${request.input} offers a draw.") },
            confirmButton = { Button(onClick = { request.complete(true) }) { Text("Accept") } },
            dismissButton = {
                OutlinedButton(onClick = { request.complete(false) }) { Text("Decline") }
            },
        )
    }

    uiModule.lastGameSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Game over") },
            text = {
                Column {
                    Text(summary.result, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(summary.reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${summary.moves.size} moves played",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = onGameEnd) { Text("Done") } },
        )
    }
}

private fun ChessColor.displayName(): String = if (this == ChessColor.WHITE) "White" else "Black"

/** Formats milliseconds as m:ss, or h:mm:ss once the clock runs past an hour. */
private fun Long.formatClock(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
