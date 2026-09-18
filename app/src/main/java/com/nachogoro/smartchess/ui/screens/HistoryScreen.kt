package com.nachogoro.smartchess.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.R
import com.nachogoro.smartchess.data.GameRecord
import com.nachogoro.smartchess.data.gameResult
import com.nachogoro.smartchess.data.toPgn
import com.nachogoro.smartchess.ui.components.ChessBoardView
import com.nachogoro.smartchess.ui.describe
import com.nachogoro.smartchess.data.termination
import com.nachogoro.smartchess.ui.components.STARTING_FEN
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Past games: a list, and a replay for whichever is selected.
 *
 * Every stored move carries the position that followed it, so stepping through a game is a matter
 * of indexing into that list; replaying involves no rules engine at all.
 */
@Composable
fun HistoryScreen(
    games: List<GameRecord>,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = games.firstOrNull { it.gameId == selectedId }

    if (selected != null) {
        GameDetail(
            game = selected,
            onDelete = {
                onDelete(selected.gameId)
                selectedId = null
            },
            onBack = { selectedId = null },
        )
    } else {
        GameList(games = games, onSelect = { selectedId = it.gameId }, onBack = onBack)
    }
}

@Composable
private fun GameList(games: List<GameRecord>, onSelect: (GameRecord) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (games.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.history_empty_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(games, key = { it.gameId }) { game ->
                    GameRow(game, onClick = { onSelect(game) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.history_back))
        }
    }
}

@Composable
private fun GameRow(game: GameRecord, onClick: () -> Unit) {
    val dateFormat = rememberListDateFormat()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.history_players,
                        game.whiteName ?: stringResource(R.string.color_white),
                        game.blackName ?: stringResource(R.string.color_black),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(game.result.ifBlank { "*" }, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    dateFormat.format(Date(game.playedAt)),
                    game.mode.lowercase(Locale.ROOT),
                    "${game.moves.size} moves",
                    game.termination().describe(LocalContext.current, game.gameResult()),
                ).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameDetail(game: GameRecord, onDelete: () -> Unit, onBack: () -> Unit) {
    val dateFormat = rememberListDateFormat()
    val moves = remember(game.gameId) { game.moves }
    // Ply 0 is the starting position; ply n shows the position after the nth move.
    var ply by remember(game.gameId) { mutableIntStateOf(moves.size) }
    var showPgn by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val fen = if (ply == 0) STARTING_FEN else moves[ply - 1].fen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            stringResource(
                R.string.history_players,
                game.whiteName ?: stringResource(R.string.color_white),
                game.blackName ?: stringResource(R.string.color_black),
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${dateFormat.format(Date(game.playedAt))} - ${game.mode.lowercase(Locale.ROOT)} - " +
                "${game.result.ifBlank { "*" }} (${game.termination().describe(LocalContext.current, game.gameResult())})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        ChessBoardView(fen = fen, flipped = false, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        Text(
            if (ply == 0) stringResource(R.string.history_starting_position) else "${moveNumberLabel(ply)} ${moves[ply - 1].san}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ply = 0 }, enabled = ply > 0) {
                Icon(Icons.Default.FirstPage, contentDescription = stringResource(R.string.history_starting_position))
            }
            IconButton(onClick = { ply-- }, enabled = ply > 0) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.history_previous_move))
            }
            Text(
                "$ply / ${moves.size}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(72.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { ply++ }, enabled = ply < moves.size) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.history_next_move))
            }
            IconButton(onClick = { ply = moves.size }, enabled = ply < moves.size) {
                Icon(Icons.Default.LastPage, contentDescription = stringResource(R.string.history_final_position))
            }
        }

        Spacer(Modifier.height(16.dp))
        MoveList(moves = moves, currentPly = ply, onSelect = { ply = it })

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showPgn = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.history_pgn))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.history_delete))
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.history_back))
        }
    }

    if (showPgn) {
        val context = LocalContext.current
        val players = stringResource(
            R.string.history_players,
            game.whiteName ?: stringResource(R.string.color_white),
            game.blackName ?: stringResource(R.string.color_black),
        )
        val chooserTitle = stringResource(R.string.history_share_pgn)
        AlertDialog(
            onDismissRequest = { showPgn = false },
            title = { Text(stringResource(R.string.history_pgn)) },
            text = {
                // PGN is wrapped at 80 columns by definition, so let it scroll rather than re-wrap
                // it to the screen and stop being PGN.
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        game.toPgn(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showPgn = false }) { Text(stringResource(R.string.history_close)) } },
            dismissButton = {
                TextButton(onClick = { sharePgn(context, game.toPgn(), subject = players, chooserTitle = chooserTitle) }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.history_share))
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.history_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.history_cancel)) }
            },
        )
    }
}

/** The score sheet. Tapping a move jumps the board to it. */
@Composable
private fun MoveList(
    moves: List<GameRecord.StoredMove>,
    currentPly: Int,
    onSelect: (Int) -> Unit,
) {
    if (moves.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        // Two plies per row, numbered by full move.
        for (moveNumber in 0 until (moves.size + 1) / 2) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${moveNumber + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
                for (side in 0..1) {
                    val ply = moveNumber * 2 + side + 1
                    if (ply <= moves.size) {
                        val isCurrent = ply == currentPly
                        TextButton(onClick = { onSelect(ply) }, modifier = Modifier.weight(1f)) {
                            Text(
                                moves[ply - 1].san,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Hands the PGN to whichever app the user picks from the system share sheet.
 *
 * Sent as plain text rather than as a `.pgn` file so that every app that can take text - a chat,
 * an email client, a notes app - is offered, which is the point of sharing over exporting. Chess
 * apps that import PGN accept it pasted just the same. The subject is the pairing, for the apps
 * that show one, such as email.
 */
private fun sharePgn(context: Context, pgn: String, subject: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, pgn)
    }
    // The chooser is always resolvable, even with nothing to offer, so this cannot throw.
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

/** `12.` for White's move, `12...` for Black's. */
private fun moveNumberLabel(ply: Int): String {
    val moveNumber = (ply + 1) / 2
    return if (ply % 2 == 1) "$moveNumber." else "$moveNumber..."
}

/**
 * The date a game finished, in the app's language.
 *
 * Built per composition from the configuration's locale rather than once from `Locale.getDefault`,
 * which is the *device's* language: with a per-app language set, a month name taken from the
 * default would be the one language on the screen that had not changed.
 */
@Composable
private fun rememberListDateFormat(): SimpleDateFormat {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = stringResource(R.string.history_date_format)
    return remember(locale, pattern) { SimpleDateFormat(pattern, locale) }
}
