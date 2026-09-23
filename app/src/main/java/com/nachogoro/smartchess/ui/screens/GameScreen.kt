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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nachogoro.smartchess.R
import com.nachogoro.smartchess.ui.AndroidUIModule
import com.nachogoro.smartchess.ui.describe
import com.nachogoro.smartchess.ui.components.BoardPiece
import com.nachogoro.smartchess.ui.components.ChessBoardView
import com.nachogoro.smartchess.ui.components.PieceTile
import com.nachogoro.smartchess.ui.components.STARTING_FEN
import kotlinx.coroutines.delay
import scp.orchestrator.GameSession
import scp.orchestrator.OrchestratorState
import scp.protocol.Color as ChessColor
import scp.protocol.GameMode
import scp.protocol.PieceType
import scp.protocol.PromotionPiece

/**
 * The in-game screen.
 *
 * The physical board is the input device: the player moves the pieces and then taps the screen to
 * say "done", exactly like pressing a chess clock. The app then reads the board over BLE and works
 * out what was played. Consequently this screen shows no interactive board, only clocks, whose
 * turn it is, and an opt-in view of the position for when a player wants to check they are in sync.
 *
 * The phone lies beside the board, so the screen is split into two halves, one facing each side of
 * the table. Each half belongs to one colour and holds that player's clock, a copy of the
 * opponent's clock that reads the right way up for them, and, for a player who is actually at the
 * board, their own controls.
 */
@Composable
fun GameScreen(
    session: GameSession?,
    orchestratorState: OrchestratorState,
    uiModule: AndroidUIModule,
    onMoveReady: () -> Unit,
    onResign: (ChessColor) -> Unit,
    onOfferDraw: () -> Unit,
    onAbort: () -> Unit,
    onGameEnd: () -> Unit,
) {
    // The orchestrator drops the session when the game ends. Keeping the last one holds the layout
    // still under the result, where the halves would otherwise swap for a player who was Black.
    var lastSession by remember { mutableStateOf(session) }
    if (session != null) lastSession = session
    val shown = session ?: lastSession

    val awaitingPlayer = orchestratorState is OrchestratorState.AwaitingPlayerMove
    val turnColor = when (orchestratorState) {
        is OrchestratorState.AwaitingPlayerMove -> orchestratorState.color
        is OrchestratorState.AwaitingOpponentMove -> orchestratorState.color
        else -> null
    }

    val incrementMs = shown?.timeControl?.incrementMs ?: 0L
    val whiteMs = rememberDisplayedClock(shown?.clocks?.whiteMs, turnColor == ChessColor.WHITE, incrementMs)
    val blackMs = rememberDisplayedClock(shown?.clocks?.blackMs, turnColor == ChessColor.BLACK, incrementMs)
    val clocks = SideClocks(whiteMs, blackMs)

    GameDialogs(uiModule, onGameEnd)

    // The whole screen is the "I have moved" target, clocks included: a player ending their move
    // reaches for the clock, exactly as on a real chess clock, and the on-screen hint promises "tap
    // anywhere". Buttons nested below still consume their own taps. Feedback is suppressed to keep
    // this calm rather than flashing on every stray tap.
    val interactionSource = remember { MutableInteractionSource() }

    // The phone lies with its bottom edge towards whoever owns it, so their half goes there, the
    // right way up, and the far one is turned to read from across the board. A local game has two
    // owners; White takes the near side there, as the board is usually set up.
    val nearColor = shown?.playerColor ?: ChessColor.WHITE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = awaitingPlayer,
                onClick = onMoveReady,
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerHalf(
                color = nearColor.opposite(),
                // Nobody sits across from the phone in an engine or online game.
                present = shown?.mode == GameMode.LOCAL,
                rotated = true,
                session = shown,
                orchestratorState = orchestratorState,
                uiModule = uiModule,
                clocks = clocks,
                turnColor = turnColor,
                onResign = onResign,
                onOfferDraw = onOfferDraw,
                onAbort = onAbort,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            HorizontalDivider()

            PlayerHalf(
                color = nearColor,
                present = true,
                rotated = false,
                session = shown,
                orchestratorState = orchestratorState,
                uiModule = uiModule,
                clocks = clocks,
                turnColor = turnColor,
                onResign = onResign,
                onOfferDraw = onOfferDraw,
                onAbort = onAbort,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** Both players' remaining time as currently displayed, so every clock on screen agrees. */
private class SideClocks(private val whiteMs: Long?, private val blackMs: Long?) {
    operator fun get(color: ChessColor): Long? =
        if (color == ChessColor.WHITE) whiteMs else blackMs
}

/**
 * The half of the screen that faces the player of [color].
 *
 * Laid out from that player's point of view, and turned round as a whole when [rotated], so the
 * controls end up on the edge nearest them. A player who is not [present] - the engine, or an
 * opponent playing from elsewhere - gets their clock and nothing to press.
 */
@Composable
private fun PlayerHalf(
    color: ChessColor,
    present: Boolean,
    rotated: Boolean,
    session: GameSession?,
    orchestratorState: OrchestratorState,
    uiModule: AndroidUIModule,
    clocks: SideClocks,
    turnColor: ChessColor?,
    onResign: (ChessColor) -> Unit,
    onOfferDraw: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showBoard by remember { mutableStateOf(false) }

    val isActive = turnColor == color
    val remaining = clocks[color]
    val lowOnTime = remaining != null && remaining < 30_000

    val background = when {
        isActive && lowOnTime -> MaterialTheme.colorScheme.errorContainer
        isActive -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(color = background, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rotate(if (rotated) 180f else 0f)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (showBoard) {
                    ChessBoardView(
                        // The live session, then the finished game: the final position is worth
                        // still being able to check once the orchestrator has let the game go.
                        fen = uiModule.lastGameSummary?.finalFen ?: session?.fen ?: STARTING_FEN,
                        // Seen from this half's side of the table.
                        flipped = color == ChessColor.BLACK,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(color.playerName(session), style = MaterialTheme.typography.labelLarge)
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

            // The far clock is upside down from here, so its time is repeated the right way up.
            // With the position on show it stands in for this player's own clock as well.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showBoard) MiniClock(color, session, clocks, turnColor)
                MiniClock(color.opposite(), session, clocks, turnColor)
            }

            if (present) {
                if (!rotated) NotificationArea(uiModule)

                StatusLine(color, session, orchestratorState)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Deliberately understated: checking the board is occasionally useful, never
                    // the point.
                    IconButton(onClick = { showBoard = !showBoard }) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = if (showBoard) {
                                stringResource(R.string.game_hide_board)
                            } else {
                                stringResource(R.string.game_show_board)
                            },
                            tint = if (showBoard) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // The protocol's draw request carries no colour: the orchestrator takes it as
                    // the side to move's, so in a local game only that side's button can mean it.
                    // That is also when the rules allow the offer, after moving and before
                    // pressing the clock.
                    val canOfferDraw = session?.mode != GameMode.LOCAL ||
                        (orchestratorState is OrchestratorState.AwaitingPlayerMove && isActive)
                    TextButton(onClick = onOfferDraw, enabled = canOfferDraw) {
                        Text(stringResource(R.string.game_draw))
                    }
                    // Resign names its colour, so it is whoever's half it is pressed in, at any time.
                    TextButton(onClick = { onResign(color) }) { Text(stringResource(R.string.game_resign)) }
                    if (session?.mode == GameMode.REMOTE) {
                        TextButton(onClick = onAbort) { Text(stringResource(R.string.game_abort)) }
                    }
                }
            }
        }
    }
}

/** The status line, with the "tap when done" hint for the half whose move it is. */
@Composable
private fun StatusLine(
    color: ChessColor,
    session: GameSession?,
    orchestratorState: OrchestratorState,
) {
    val statusText = when (orchestratorState) {
        is OrchestratorState.Idle -> stringResource(R.string.game_waiting)
        is OrchestratorState.SeekingGame -> stringResource(R.string.game_seeking)
        is OrchestratorState.AwaitingPlayerMove ->
            stringResource(R.string.game_to_move, orchestratorState.color.displayName())

        // An engine is computing rather than merely absent, so say so - "waiting for Black"
        // reads like a stall when the phone is in fact busy searching.
        is OrchestratorState.AwaitingOpponentMove ->
            if (session?.mode == GameMode.ENGINE) {
                stringResource(R.string.game_engine_thinking)
            } else {
                stringResource(R.string.game_waiting_for, orchestratorState.color.displayName())
            }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        statusText,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (orchestratorState is OrchestratorState.AwaitingPlayerMove && orchestratorState.color == color) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.game_move_then_tap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** One side's time in a single line, for reading a clock that faces the other way. */
@Composable
private fun MiniClock(
    color: ChessColor,
    session: GameSession?,
    clocks: SideClocks,
    turnColor: ChessColor?,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            color.playerName(session),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            clocks[color]?.formatClock() ?: "--:--",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (turnColor == color) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The remaining time to show for one side.
 *
 * [timeMs] is the snapshot from the last move. The displayed value resets to it whenever new clock
 * values arrive, ticks down only while this side is active, and otherwise holds, including gaining
 * [incrementMs] when this side completes a move.
 *
 * This matters for remote games: the server is the clock authority and reports the times only on
 * the opponent's move, so [timeMs] does not change when we move. Between our move and the
 * opponent's reply the snapshot for our side is stale, so we hold the ticked value and credit the
 * increment the move earned, giving the time we actually have until the server figure arrives.
 * Local and engine games refresh [timeMs] every move (already with the increment), so nothing is
 * held or credited there.
 *
 * Worked out once per side and handed to every clock showing it, so the full-size clock and its
 * small copy in the other half cannot disagree.
 */
@Composable
private fun rememberDisplayedClock(timeMs: Long?, isActive: Boolean, incrementMs: Long): Long? {
    var displayMs by remember(timeMs) { mutableStateOf(timeMs) }

    LaunchedEffect(timeMs, isActive) {
        val base = timeMs ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            displayMs = (base - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0)
            delay(200)
        }
    }

    // Credit the increment when this side stops being active without a fresh base (we moved).
    var wasActive by remember(timeMs) { mutableStateOf(isActive) }
    LaunchedEffect(isActive) {
        if (wasActive && !isActive) {
            displayMs?.let { displayMs = it + incrementMs }
        }
        wasActive = isActive
    }

    return displayMs
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
                    stringResource(R.string.game_opponent_played, opponentMove.san),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.game_replay_on_board),
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
                    stringResource(R.string.game_desync_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    desync.message.describe(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { uiModule.clearDesync() }) { Text(stringResource(R.string.game_dismiss)) }
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
                    error.describe(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { uiModule.clearError() }) { Text(stringResource(R.string.game_dismiss)) }
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
            title = { Text(stringResource(R.string.game_promotion_question)) },
            text = {
                // Pictures rather than names: the player has just put one of these on the board,
                // and four words do not fit across a dialog - "Knight" was wrapping to one letter
                // per line. They are shown in the promoting player's own colour.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (piece in PromotionPiece.entries) {
                        // A quarter of the dialog each, kept square. Fixed sizes would overflow
                        // the narrowest dialog a small screen allows.
                        PieceTile(
                            piece = BoardPiece(piece.boardType(), request.input.color),
                            contentDescription = piece.displayName(),
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { request.complete(piece) },
                        )
                    }
                }
            },
            // The pieces are the buttons; there is nothing left to confirm.
            confirmButton = {},
        )
    }

    // Two legal moves can produce the same physical arrangement; only the player knows which.
    uiModule.pendingDisambiguation?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.game_disambiguation_title)) },
            text = { Text(stringResource(R.string.game_disambiguation_explanation)) },
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
            title = { Text(stringResource(R.string.game_draw_offered_title)) },
            text = {
                Text(stringResource(R.string.game_draw_offered, request.input.describe(LocalContext.current)))
            },
            confirmButton = { Button(onClick = { request.complete(true) }) { Text(stringResource(R.string.game_accept)) } },
            dismissButton = {
                OutlinedButton(onClick = { request.complete(false) }) { Text(stringResource(R.string.game_decline)) }
            },
        )
    }

    uiModule.lastGameSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.game_over_title)) },
            text = {
                Column {
                    Text(summary.result.protocolString, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        summary.termination.describe(LocalContext.current, summary.result),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.game_moves_played, summary.moves.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = onGameEnd) { Text(stringResource(R.string.game_done)) } },
        )
    }
}

private fun ChessColor.opposite(): ChessColor =
    if (this == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE

@Composable
private fun ChessColor.playerName(session: GameSession?): String =
    (if (this == ChessColor.WHITE) session?.whiteName else session?.blackName) ?: displayName()

@Composable
private fun ChessColor.displayName(): String =
    stringResource(if (this == ChessColor.WHITE) R.string.color_white else R.string.color_black)

private fun PromotionPiece.boardType(): PieceType = when (this) {
    PromotionPiece.QUEEN -> PieceType.QUEEN
    PromotionPiece.ROOK -> PieceType.ROOK
    PromotionPiece.BISHOP -> PieceType.BISHOP
    PromotionPiece.KNIGHT -> PieceType.KNIGHT
}

/** Names the piece for a screen reader, which the image on its own cannot. */
@Composable
private fun PromotionPiece.displayName(): String = stringResource(
    when (this) {
        PromotionPiece.QUEEN -> R.string.piece_name_queen
        PromotionPiece.ROOK -> R.string.piece_name_rook
        PromotionPiece.BISHOP -> R.string.piece_name_bishop
        PromotionPiece.KNIGHT -> R.string.piece_name_knight
    }
)

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
