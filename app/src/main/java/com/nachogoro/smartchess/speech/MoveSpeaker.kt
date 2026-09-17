package com.nachogoro.smartchess.speech

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.os.LocaleList
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatDelegate
import com.nachogoro.smartchess.R
import scp.protocol.ShowOpponentMoveParams
import java.util.Locale

/**
 * Reads the opponent's move out loud.
 *
 * The player is looking at a physical board, not at the phone, so the move has to reach them
 * without their picking it up. The wording follows algebraic notation's own rule, saying no more
 * than the move needs to be unambiguous, because that is what a player is used to hearing.
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
     * If a move arrives before the engine is ready, we store it here to speak it once we are ready.
     * A newer one replaces it, as an announcement is only worth hearing while it is still the
     * position on the board.
     */
    private var pending: Utterance? = null

    /** What to say and the language to say it in, which travel together for the reason in [speak]. */
    private data class Utterance(val languageTag: String, val text: String)

    init {
        engine = TextToSpeech(this.context) { status ->
            // Posted rather than handled inline to avoid the callback arriving before the
            // constructor returns (and hence before engine is assigned).
            mainHandler.post { onEngineInit(status) }
        }
    }

    /** Speaks [params] if notation can be read, else stays silent. */
    fun announce(params: ShowOpponentMoveParams) {
        val announcement = parseMoveAnnouncement(params.san, params.move, params.isEnPassant)
            ?: return
        val strings = localizedResources()
        speak(
            Utterance(
                languageTag = strings.getString(R.string.announce_locale),
                text = announcement.describe(vocabulary(strings)),
            )
        )
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

        isReady = true
        pending?.let {
            pending = null
            speak(it)
        }
    }

    private fun speak(utterance: Utterance) {
        val tts = engine
        if (tts == null || !isReady) {
            pending = utterance
            return
        }
        
        // Set per utterance rather than once at startup because the application can change languages,
        // and this object is not recreated.
        tts.setLanguage(Locale.forLanguageTag(utterance.languageTag))
        
        // Flush rather than queue (anything old still being spoken can only happen if it is a move
        // the player has already played on the board).
        tts.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Resources in the language the app is set to.
     */
    private fun localizedResources(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return context.createConfigurationContext(configuration)
    }

    /**
     * Read afresh for every announcement so that a language change takes effect immediately,
     * rather than at the next restart.
     */
    private fun vocabulary(strings: Context) = AnnouncementVocabulary(
        pieceNames = mapOf(
            AnnouncedPiece.PAWN to strings.getString(R.string.piece_pawn),
            AnnouncedPiece.KNIGHT to strings.getString(R.string.piece_knight),
            AnnouncedPiece.BISHOP to strings.getString(R.string.piece_bishop),
            AnnouncedPiece.ROOK to strings.getString(R.string.piece_rook),
            AnnouncedPiece.QUEEN to strings.getString(R.string.piece_queen),
            AnnouncedPiece.KING to strings.getString(R.string.piece_king),
        ),
        move = strings.getString(R.string.announce_move),
        moveFrom = strings.getString(R.string.announce_move_from),
        capture = strings.getString(R.string.announce_capture),
        captureFrom = strings.getString(R.string.announce_capture_from),
        castleKingside = strings.getString(R.string.announce_castle_kingside),
        castleQueenside = strings.getString(R.string.announce_castle_queenside),
        enPassant = strings.getString(R.string.announce_en_passant),
        promotion = strings.getString(R.string.announce_promotion),
        check = strings.getString(R.string.announce_check),
        checkmate = strings.getString(R.string.announce_checkmate),
    )

    private companion object {
        const val UTTERANCE_ID = "opponent-move"
    }
}
