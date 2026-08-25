package com.nachogoro.smartchess.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.R
import com.nachogoro.smartchess.ble.SquareReading
import com.nachogoro.smartchess.ble.toPlacement
import com.nachogoro.smartchess.core.BoardReading
import com.nachogoro.smartchess.ui.components.DARK_SQUARE
import com.nachogoro.smartchess.ui.components.LIGHT_SQUARE

/** Width of the rank labels down the side of the board; also the offset of the file labels. */
private val LABEL_WIDTH = 18.dp

/**
 * A raw read of the physical board, on demand.
 *
 * This is a diagnostic, not part of playing: it exists to check that what the sensors report is
 * what the app decodes, so it shows the bytes exactly as they arrived and the position they
 * decoded to, side by side, and reads nothing unless asked. Nothing here goes through the
 * orchestrator, and a reading that a game would refuse - an unreadable square, a packet that does
 * not parse - is shown rather than rejected, since those are the readings worth looking at.
 *
 * The board only reports colour, so the squares are drawn as plain discs: the piece on a square is
 * not something the hardware knows.
 */
@Composable
fun BoardDebugScreen(
    reading: BoardReading?,
    isReading: Boolean,
    boardReady: Boolean,
    onRead: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRead,
            enabled = boardReady && !isReading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (isReading) stringResource(R.string.debug_reading)
                else stringResource(R.string.debug_read)
            )
        }

        if (!boardReady) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.debug_needs_board),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // The bytes come first: everything below is an interpretation of them, and reading the two
        // against each other is the point of the screen.
        RawBytesCard(reading)
        Spacer(Modifier.height(12.dp))

        reading?.error?.let { error ->
            Text(
                // A read that never returned has no bytes to show; one that returned something
                // undecodable does, and the two failures are worth telling apart.
                text = if (reading.bytes.isEmpty()) {
                    stringResource(R.string.debug_read_failed, error)
                } else {
                    stringResource(R.string.debug_decode_failed, error)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
        }

        reading?.squares?.let { squares ->
            BoardReadingView(squares)
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(
                    R.string.debug_counts,
                    squares.count { it.value == SquareReading.WHITE },
                    squares.count { it.value == SquareReading.BLACK },
                    squares.count { it.value == SquareReading.EMPTY },
                    squares.count { it.value == SquareReading.UNKNOWN },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            // The same one-line form the connection logs on every read, so a session at the board
            // and the logcat afterwards can be compared line for line.
            Text(stringResource(R.string.debug_decoded), style = MaterialTheme.typography.labelMedium)
            Text(
                text = squares.toPlacement(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (reading == null) {
            Text(
                stringResource(R.string.debug_no_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.debug_back))
        }
    }
}

/** The packet as it arrived, in hex, or a placeholder before anything has been read. */
@Composable
private fun RawBytesCard(reading: BoardReading?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.debug_bytes), style = MaterialTheme.typography.labelMedium)
                reading?.let {
                    Text(
                        stringResource(R.string.debug_read_index, it.index),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // Wrapped by hand rather than left to the text layout, so a byte always lands in
                // the same column and two reads can be compared by eye.
                text = reading?.bytes?.takeIf { it.isNotEmpty() }?.toHexLines() ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The 64 readings on a board.
 *
 * Drawn from rank 8 down, White at the bottom, with the files and ranks labelled: an orientation
 * mistake in the packet decoding looks like a plausible position otherwise, and the labels are
 * what make it visible against the physical board.
 */
@Composable
private fun BoardReadingView(squares: Map<String, SquareReading>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (rank in 8 downTo 1) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(LABEL_WIDTH).align(Alignment.CenterVertically),
                )
                for (file in 'a'..'h') {
                    SquareCell(
                        square = "$file$rank",
                        reading = squares["$file$rank"],
                        isLight = ((file - 'a') + rank) % 2 != 0,
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(LABEL_WIDTH))
            for (file in 'a'..'h') {
                Text(
                    text = "$file",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SquareCell(
    square: String,
    reading: SquareReading?,
    isLight: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = reading.describe(square)
    Box(
        modifier = modifier
            .background(if (isLight) LIGHT_SQUARE else DARK_SQUARE)
            .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label }),
        contentAlignment = Alignment.Center,
    ) {
        when (reading) {
            // An unreadable square is not an empty one, and the difference is what a sensor check
            // is looking for, so it is marked rather than left blank.
            SquareReading.UNKNOWN -> Text(
                text = "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )

            SquareReading.WHITE, SquareReading.BLACK -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (reading == SquareReading.WHITE) Color.White else Color.Black)
                    // The discs sit on squares of a similar tone at both ends, so each carries an
                    // outline in the other's colour to stay visible.
                    .border(
                        1.dp,
                        if (reading == SquareReading.WHITE) Color.Black else Color.White,
                        CircleShape,
                    )
            )

            SquareReading.EMPTY, null -> Unit
        }
    }
}

/**
 * What a square reads as, for a screen reader. Null on an empty square: there is nothing there to
 * announce, and announcing all 64 squares would bury the ones that hold something.
 */
@Composable
private fun SquareReading?.describe(square: String): String? {
    val name = when (this) {
        SquareReading.WHITE -> stringResource(R.string.debug_square_white)
        SquareReading.BLACK -> stringResource(R.string.debug_square_black)
        SquareReading.UNKNOWN -> stringResource(R.string.debug_square_unknown)
        SquareReading.EMPTY, null -> return null
    }
    return stringResource(R.string.debug_square_at, name, square)
}

/** Hex, eight bytes to a line. */
private fun ByteArray.toHexLines(): String =
    toList().chunked(8).joinToString("\n") { line ->
        line.joinToString(" ") { "%02X".format(it) }
    }
