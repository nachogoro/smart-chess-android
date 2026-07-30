package com.nachogoro.smartchess.speech

/**
 * A move broken down into the parts an announcement is made of.
 *
 * Deliberately free of any wording: [describe] turns this into a sentence using a
 * [AnnouncementVocabulary], so translating the app means translating the vocabulary and nothing
 * else.
 */
data class MoveAnnouncement(
    val piece: AnnouncedPiece,
    /**
     * The origin square, but only when the move needs it spoken.
     *
     * Null for the common case where naming the piece and its destination identifies the move on
     * its own. See [parseMoveAnnouncement] for when it is filled in.
     */
    val from: String?,
    val to: String,
    val isCapture: Boolean,
    val isEnPassant: Boolean,
    val promotion: AnnouncedPiece?,
    val castling: Castling?,
    val check: CheckState,
)

enum class AnnouncedPiece { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

enum class Castling { KINGSIDE, QUEENSIDE }

enum class CheckState { NONE, CHECK, CHECKMATE }

/**
 * Every phrase an announcement can be assembled from.
 *
 * Templates use positional placeholders so a translation can reorder them.
 */
data class AnnouncementVocabulary(
    val pieceNames: Map<AnnouncedPiece, String>,
    /** piece, destination. */
    val move: String,
    /** piece, origin, destination. */
    val moveFrom: String,
    /** piece, destination. */
    val capture: String,
    /** piece, origin, destination. */
    val captureFrom: String,
    val castleKingside: String,
    val castleQueenside: String,
    /** the move so far. */
    val enPassant: String,
    /** the move so far, the piece promoted to. */
    val promotion: String,
    /** the move so far. */
    val check: String,
    /** the move so far. */
    val checkmate: String,
)

/**
 * Reads a played move into its spoken parts.
 *
 * [san] carries everything except the full origin square: standard algebraic notation includes a
 * disambiguator only when one is needed, and then only the file or the rank of it. Its presence is
 * the signal that the origin matters; [uci] supplies the whole square, because "knight on e6" is
 * easier to act on at a physical board than "knight on the e file".
 *
 * A pawn capture always carries its file in SAN whether or not another pawn could have made the
 * same capture, so those are always announced with their origin square. That costs two syllables
 * and is never wrong.
 *
 * [isEnPassant] cannot be recovered from either notation and is reported separately by the game
 * controller.
 *
 * Returns null for notation this cannot make sense of, which means the move goes unannounced
 * rather than mis-announced.
 */
fun parseMoveAnnouncement(
    san: String,
    uci: String,
    isEnPassant: Boolean = false,
): MoveAnnouncement? {
    if (uci.length !in 4..5) return null
    val origin = uci.take(2)
    if (!origin.isSquare()) return null

    // The rules library appends "(=)" to a move that comes with a draw offer. The offer reaches
    // the player through its own prompt, so it is not part of the move.
    var text = san.removeSuffix("(=)")

    val check = when {
        text.endsWith("#") -> CheckState.CHECKMATE
        text.endsWith("+") -> CheckState.CHECK
        else -> CheckState.NONE
    }
    text = text.trimEnd('+', '#')

    val castling = when (text) {
        "O-O" -> Castling.KINGSIDE
        "O-O-O" -> Castling.QUEENSIDE
        else -> null
    }
    if (castling != null) {
        val destination = uci.substring(2, 4)
        if (!destination.isSquare()) return null
        return MoveAnnouncement(
            piece = AnnouncedPiece.KING,
            from = null,
            to = destination,
            isCapture = false,
            isEnPassant = false,
            promotion = null,
            castling = castling,
            check = check,
        )
    }

    val promotion = if (text.length >= 2 && text[text.length - 2] == '=') {
        pieceFromLetter(text.last()) ?: return null
    } else {
        null
    }
    if (promotion != null) text = text.dropLast(2)

    // No leading letter means a pawn: SAN writes pawn moves as the bare square.
    val piece = pieceFromLetter(text.firstOrNull() ?: return null)
    if (piece != null) text = text.drop(1)

    if (text.length < 2) return null
    val destination = text.takeLast(2)
    if (!destination.isSquare()) return null

    val head = text.dropLast(2)
    val isCapture = head.endsWith("x")
    val disambiguator = head.removeSuffix("x")

    return MoveAnnouncement(
        piece = piece ?: AnnouncedPiece.PAWN,
        from = if (disambiguator.isNotEmpty()) origin else null,
        to = destination,
        isCapture = isCapture,
        isEnPassant = isEnPassant,
        promotion = promotion,
        castling = null,
        check = check,
    )
}

/** Assembles the sentence to speak. */
fun MoveAnnouncement.describe(vocabulary: AnnouncementVocabulary): String {
    val pieceName = vocabulary.pieceNames.getValue(piece)

    var text = when {
        castling == Castling.KINGSIDE -> vocabulary.castleKingside
        castling == Castling.QUEENSIDE -> vocabulary.castleQueenside
        isCapture && from != null -> vocabulary.captureFrom.format(pieceName, from, to)
        isCapture -> vocabulary.capture.format(pieceName, to)
        // A pawn push that needs no disambiguation is named by its destination alone, the way a
        // player says it over the board: "e4", not "pawn to e4".
        piece == AnnouncedPiece.PAWN && from == null -> to
        from != null -> vocabulary.moveFrom.format(pieceName, from, to)
        else -> vocabulary.move.format(pieceName, to)
    }

    if (isEnPassant) text = vocabulary.enPassant.format(text)
    if (promotion != null) {
        text = vocabulary.promotion.format(text, vocabulary.pieceNames.getValue(promotion))
    }
    text = when (check) {
        CheckState.CHECK -> vocabulary.check.format(text)
        CheckState.CHECKMATE -> vocabulary.checkmate.format(text)
        CheckState.NONE -> text
    }

    return text
}

private fun pieceFromLetter(letter: Char): AnnouncedPiece? = when (letter) {
    'K' -> AnnouncedPiece.KING
    'Q' -> AnnouncedPiece.QUEEN
    'R' -> AnnouncedPiece.ROOK
    'B' -> AnnouncedPiece.BISHOP
    'N' -> AnnouncedPiece.KNIGHT
    else -> null
}

private fun String.isSquare(): Boolean =
    length == 2 && this[0] in 'a'..'h' && this[1] in '1'..'8'
