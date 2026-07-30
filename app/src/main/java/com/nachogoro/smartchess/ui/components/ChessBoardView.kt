package com.nachogoro.smartchess.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nachogoro.smartchess.R
import scp.protocol.Color as ChessColor
import scp.protocol.PieceType

private val LIGHT_SQUARE = Color(0xFFF0D9B5)
private val DARK_SQUARE = Color(0xFFB58863)

data class BoardPiece(val type: PieceType, val color: ChessColor)

/**
 * Read-only board rendered from a FEN string.
 *
 * This exists purely so a player can confirm that the physical board matches what the app thinks
 * the position is. It is never the input surface - moves are always made on the real board - so it
 * has no interaction and is shown only when the player asks for it.
 */
@Composable
fun ChessBoardView(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
) {
    val pieces = remember(fen) { parseFenPlacement(fen) }

    val ranks = if (flipped) (1..8).toList() else (8 downTo 1).toList()
    val files = if (flipped) ('h' downTo 'a').toList() else ('a'..'h').toList()

    Column(modifier = modifier.aspectRatio(1f)) {
        for (rank in ranks) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                for (file in files) {
                    val square = "$file$rank"
                    // Colour depends on absolute coordinates, so flipping the view does not
                    // recolour the board.
                    val isLight = ((file - 'a') + rank) % 2 != 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(if (isLight) LIGHT_SQUARE else DARK_SQUARE),
                        contentAlignment = Alignment.Center,
                    ) {
                        pieces[square]?.let { piece ->
                            Image(
                                painter = painterResource(piece.drawableRes()),
                                contentDescription = piece.contentDescription(square),
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One piece on a board-coloured square, for showing a piece away from the board.
 *
 * The square is not decoration. The piece images are solid silhouettes carrying no outline of
 * their own, so a black piece drawn straight onto a dark theme's surface would be invisible.
 */
@Composable
fun PieceTile(
    piece: BoardPiece,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(LIGHT_SQUARE),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(piece.drawableRes()),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Extracts the piece placement from a FEN string.
 *
 * Only the first field matters here; the rest (side to move, castling rights, clocks) is the
 * rules engine's business.
 */
fun parseFenPlacement(fen: String): Map<String, BoardPiece> {
    val placement = fen.trim().substringBefore(' ')
    val pieces = mutableMapOf<String, BoardPiece>()

    var rank = 8
    for (row in placement.split('/')) {
        if (rank < 1) break
        var fileIndex = 0
        for (symbol in row) {
            when {
                symbol.isDigit() -> fileIndex += symbol - '0'
                else -> {
                    if (fileIndex in 0..7) {
                        val square = "${'a' + fileIndex}$rank"
                        symbol.toBoardPiece()?.let { pieces[square] = it }
                    }
                    fileIndex++
                }
            }
        }
        rank--
    }
    return pieces
}

private fun Char.toBoardPiece(): BoardPiece? {
    val type = when (lowercaseChar()) {
        'p' -> PieceType.PAWN
        'n' -> PieceType.KNIGHT
        'b' -> PieceType.BISHOP
        'r' -> PieceType.ROOK
        'q' -> PieceType.QUEEN
        'k' -> PieceType.KING
        else -> return null
    }
    // Upper case is White in FEN.
    val color = if (isUpperCase()) ChessColor.WHITE else ChessColor.BLACK
    return BoardPiece(type, color)
}

private fun BoardPiece.drawableRes(): Int = when (color) {
    ChessColor.WHITE -> when (type) {
        PieceType.PAWN -> R.drawable.piece_pawn_white
        PieceType.KNIGHT -> R.drawable.piece_knight_white
        PieceType.BISHOP -> R.drawable.piece_bishop_white
        PieceType.ROOK -> R.drawable.piece_rook_white
        PieceType.QUEEN -> R.drawable.piece_queen_white
        PieceType.KING -> R.drawable.piece_king_white
    }

    ChessColor.BLACK -> when (type) {
        PieceType.PAWN -> R.drawable.piece_pawn_black
        PieceType.KNIGHT -> R.drawable.piece_knight_black
        PieceType.BISHOP -> R.drawable.piece_bishop_black
        PieceType.ROOK -> R.drawable.piece_rook_black
        PieceType.QUEEN -> R.drawable.piece_queen_black
        PieceType.KING -> R.drawable.piece_king_black
    }
}

@Composable
private fun BoardPiece.contentDescription(square: String): String = stringResource(
    R.string.board_piece_on_square,
    stringResource(if (color == ChessColor.WHITE) R.string.color_white else R.string.color_black),
    stringResource(
        when (type) {
            PieceType.PAWN -> R.string.piece_name_pawn
            PieceType.KNIGHT -> R.string.piece_name_knight
            PieceType.BISHOP -> R.string.piece_name_bishop
            PieceType.ROOK -> R.string.piece_name_rook
            PieceType.QUEEN -> R.string.piece_name_queen
            PieceType.KING -> R.string.piece_name_king
        }
    ),
    square,
)

/** Standard starting position, used before the first move is known. */
const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
