package com.nachogoro.smartchess.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The announcements as a player would hear them.
 *
 * The vocabulary below mirrors `res/values/strings.xml`; it is repeated here so the tests state
 * the English wording outright rather than asserting on placeholders.
 */
class MoveAnnouncementTest {

    private val english = AnnouncementVocabulary(
        pieceNames = mapOf(
            AnnouncedPiece.PAWN to "Pawn",
            AnnouncedPiece.KNIGHT to "Knight",
            AnnouncedPiece.BISHOP to "Bishop",
            AnnouncedPiece.ROOK to "Rook",
            AnnouncedPiece.QUEEN to "Queen",
            AnnouncedPiece.KING to "King",
        ),
        move = "%1\$s to %2\$s",
        moveFrom = "%1\$s on %2\$s to %3\$s",
        capture = "%1\$s captures on %2\$s",
        captureFrom = "%1\$s on %2\$s captures on %3\$s",
        castleKingside = "Castles kingside",
        castleQueenside = "Castles queenside",
        enPassant = "%1\$s, en passant",
        promotion = "%1\$s, promoting to a %2\$s",
        check = "%1\$s, check",
        checkmate = "%1\$s, checkmate",
    )

    private fun announce(san: String, uci: String, isEnPassant: Boolean = false): String? =
        parseMoveAnnouncement(san, uci, isEnPassant)?.describe(english)

    // -- Say no more than the move needs --

    @Test
    fun `unambiguous pawn move is just its square`() {
        assertEquals("e4", announce("e4", "e2e4"))
    }

    @Test
    fun `unambiguous piece move names the piece and destination`() {
        assertEquals("Bishop to e6", announce("Be6", "c8e6"))
        assertEquals("Queen to h5", announce("Qh5", "d1h5"))
        assertEquals("King to e2", announce("Ke2", "e1e2"))
    }

    @Test
    fun `ambiguous piece move names the whole origin square`() {
        // SAN disambiguates by file alone; the announcement gives the square, which is easier to
        // find on a board than "the knight on the e file".
        assertEquals("Knight on e6 to g8", announce("Neg8", "e6g8"))
    }

    @Test
    fun `rank disambiguation is also spoken as the whole square`() {
        assertEquals("Rook on c6 to c8", announce("R6c8", "c6c8"))
    }

    @Test
    fun `unambiguous capture does not name the origin`() {
        assertEquals("Rook captures on b2", announce("Rxb2", "b8b2"))
    }

    @Test
    fun `ambiguous capture names the origin`() {
        assertEquals("Rook on c6 captures on c8", announce("Rcxc8", "c6c8"))
    }

    @Test
    fun `pawn captures always name the origin`() {
        // SAN always carries the pawn's file on a capture, ambiguous or not, so these are always
        // announced in full.
        assertEquals("Pawn on e4 captures on d5", announce("exd5", "e4d5"))
    }

    // -- Everything the player has to act on --

    @Test
    fun `castling is announced by side`() {
        assertEquals("Castles kingside", announce("O-O", "e1g1"))
        assertEquals("Castles queenside", announce("O-O-O", "e1c1"))
    }

    @Test
    fun `en passant is called out`() {
        assertEquals(
            "Pawn on e5 captures on d6, en passant",
            announce("exd6", "e5d6", isEnPassant = true),
        )
    }

    @Test
    fun `promotion keeps the terse pawn form`() {
        assertEquals("e8, promoting to a Queen", announce("e8=Q", "e7e8q"))
    }

    @Test
    fun `capturing promotion names the pawn and its origin`() {
        assertEquals(
            "Pawn on e7 captures on d8, promoting to a Queen, check",
            announce("exd8=Q+", "e7d8q"),
        )
    }

    @Test
    fun `check and checkmate are announced`() {
        assertEquals("Knight captures on e4, check", announce("Nxe4+", "f6e4"))
        assertEquals("Queen to f7, checkmate", announce("Qf7#", "h5f7"))
        assertEquals("Castles kingside, checkmate", announce("O-O#", "e1g1"))
        assertEquals("e8, promoting to a Knight, checkmate", announce("e8=N#", "e7e8n"))
    }

    @Test
    fun `a draw offer is not part of the move`() {
        // The rules library appends "(=)" when the move carries a draw offer; the offer reaches
        // the player through its own prompt.
        assertEquals("e4", announce("e4(=)", "e2e4"))
        assertEquals("Bishop to e6", announce("Be6(=)", "c8e6"))
    }

    // -- Notation this cannot read goes unannounced rather than mis-announced --

    @Test
    fun `unreadable notation yields nothing`() {
        assertNull(announce("", "e2e4"))
        assertNull(announce("e4", "e2"))
        assertNull(announce("Nz9", "e6g8"))
        assertNull(announce("e8=X", "e7e8"))
    }
}
