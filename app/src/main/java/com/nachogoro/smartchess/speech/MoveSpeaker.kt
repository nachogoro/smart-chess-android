package com.nachogoro.smartchess.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.nachogoro.smartchess.R
import scp.protocol.ShowOpponentMoveParams
import java.util.Locale

/**
 * Reads the opponent's move out loud.
 *
 * The player is looking at a physical board, not at the phone, so the move has to reach them
 * without their picking it up. The wording follows algebraic notation's own rule - say no more
 * than the move needs to be unambiguous - because that is what a player is used to hearing.
 *
 * Construct on the main thread and call [announce] from it; the engine callback is posted there
 * and nothing here is synchronised.
 */
class MoveSpeaker(context: Context) {

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var isReady = false

    /**
     * Held only while the engine starts up.
     *
     * A move that arrives first is spoken once the engine is ready rather than dropped, and a
     * newer one replaces it: an announcement is only worth hearing while it is still the position
     * on the board.
     */
    private var pending: String? = null

    init {
        engine = TextToSpeech(this.context) { status ->
            // Posted rather than handled inline: the callback may arrive before the constructor
            // has returned, and `engine` is assigned only once it has.
            mainHandler.post { onEngineInit(status) }
        }
    }

    /** Speaks [params], unless the notation cannot be read - in which case it stays silent. */
    fun announce(params: ShowOpponentMoveParams) {
        val announcement = parseMoveAnnouncement(params.san, params.move, params.isEnPassant)
            ?: return
        speak(announcement.describe(vocabulary()))
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        isReady = false
        pending = null
    }

    private fun onEngineInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) return

        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        // Whatever the engine makes of the language is accepted: a move read with the wrong accent
        // is still worth more than silence.
        tts.setLanguage(Locale.forLanguageTag(context.getString(R.string.announce_locale)))

        isReady = true
        pending?.let {
            pending = null
            speak(it)
        }
    }

    private fun speak(text: String) {
        val tts = engine
        if (tts == null || !isReady) {
            pending = text
            return
        }
        // Flush rather than queue: one move arrives per turn, so anything still being spoken is a
        // move the player has already played on the board.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Read afresh for every announcement so that a language change takes effect immediately,
     * rather than at the next restart.
     */
    private fun vocabulary() = AnnouncementVocabulary(
        pieceNames = mapOf(
            AnnouncedPiece.PAWN to context.getString(R.string.piece_pawn),
            AnnouncedPiece.KNIGHT to context.getString(R.string.piece_knight),
            AnnouncedPiece.BISHOP to context.getString(R.string.piece_bishop),
            AnnouncedPiece.ROOK to context.getString(R.string.piece_rook),
            AnnouncedPiece.QUEEN to context.getString(R.string.piece_queen),
            AnnouncedPiece.KING to context.getString(R.string.piece_king),
        ),
        move = context.getString(R.string.announce_move),
        moveFrom = context.getString(R.string.announce_move_from),
        capture = context.getString(R.string.announce_capture),
        captureFrom = context.getString(R.string.announce_capture_from),
        castleKingside = context.getString(R.string.announce_castle_kingside),
        castleQueenside = context.getString(R.string.announce_castle_queenside),
        enPassant = context.getString(R.string.announce_en_passant),
        promotion = context.getString(R.string.announce_promotion),
        check = context.getString(R.string.announce_check),
        checkmate = context.getString(R.string.announce_checkmate),
    )

    private companion object {
        const val UTTERANCE_ID = "opponent-move"
    }
}
