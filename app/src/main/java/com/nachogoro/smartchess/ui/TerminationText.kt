package com.nachogoro.smartchess.ui

import android.content.Context
import com.nachogoro.smartchess.R
import scp.protocol.DrawOffer
import scp.protocol.GameResult
import scp.protocol.Termination

/**
 * How a game ended, in words.
 *
 * The wording lives here because the UI module is the only one allowed to write it: every other
 * SCP module reports a code, since none of them can know who is reading (PROTOCOL.md §2.6).
 *
 * [result] is read because a termination is a cause and nothing more: whether the game was drawn
 * is the result's to say, and it says so even where the cause has no name.
 */
fun Termination.describe(context: Context, result: GameResult): String = when (this) {
    Termination.Checkmate -> context.getString(R.string.termination_checkmate)
    Termination.Stalemate -> context.getString(R.string.termination_stalemate)
    Termination.Resignation -> context.getString(R.string.termination_resignation)
    Termination.Timeout -> context.getString(R.string.termination_timeout)
    Termination.Aborted -> context.getString(R.string.termination_aborted)
    Termination.Agreement -> context.getString(R.string.draw_agreement)
    Termination.InsufficientMaterial -> context.getString(R.string.draw_insufficient_material)
    Termination.FiftyMoveRule -> context.getString(R.string.draw_fifty_move_rule)
    Termination.SeventyFiveMoveRule -> context.getString(R.string.draw_seventy_five_move_rule)
    Termination.ThreefoldRepetition -> context.getString(R.string.draw_threefold_repetition)
    Termination.FivefoldRepetition -> context.getString(R.string.draw_fivefold_repetition)
    // Whoever ended the game named something this build does not know. Their own words, or failing
    // that their identifier, beats inventing a reason for them - and neither is ours to translate.
    // A drawn game whose rule nobody named - which is most of what Lichess reports - still reads as
    // a draw, because the drawn-ness never lived in the cause.
    is Termination.Other -> text
        ?: authorityCode
        ?: if (result == GameResult.DRAW) context.getString(R.string.draw_unspecified)
           else context.getString(R.string.termination_unknown)
}

/**
 * Who offered a draw, in words.
 *
 * Their name if there is one, since it is theirs and not ours to translate; otherwise the colour,
 * and failing that the opponent. The core sends what it knows and leaves the sentence here.
 */
fun DrawOffer.describe(context: Context): String =
    name ?: when (color) {
        scp.protocol.Color.WHITE -> context.getString(R.string.color_white)
        scp.protocol.Color.BLACK -> context.getString(R.string.color_black)
        null -> context.getString(R.string.draw_offer_opponent)
    }
