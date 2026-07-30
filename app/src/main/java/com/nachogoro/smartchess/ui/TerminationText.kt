package com.nachogoro.smartchess.ui

import android.content.Context
import com.nachogoro.smartchess.R
import scp.protocol.DrawKind
import scp.protocol.DrawOffer
import scp.protocol.Termination

/**
 * How a game ended, in words.
 *
 * The wording lives here because the UI module is the only one allowed to write it: every other
 * SCP module reports a code, since none of them can know who is reading (PROTOCOL.md §2.6).
 */
fun Termination.describe(context: Context): String = when (this) {
    Termination.Checkmate -> context.getString(R.string.termination_checkmate)
    Termination.Stalemate -> context.getString(R.string.termination_stalemate)
    Termination.Resignation -> context.getString(R.string.termination_resignation)
    Termination.Timeout -> context.getString(R.string.termination_timeout)
    Termination.Aborted -> context.getString(R.string.termination_aborted)
    is Termination.Drawn -> kind.describe(context)
    // Whoever ended the game named something this build does not know. Their own words, or failing
    // that their identifier, beats inventing a reason for them - and neither is ours to translate.
    is Termination.Other -> text ?: authorityCode ?: context.getString(R.string.termination_unknown)
}

private fun DrawKind.describe(context: Context): String = when (this) {
    DrawKind.Agreement -> context.getString(R.string.draw_agreement)
    DrawKind.InsufficientMaterial -> context.getString(R.string.draw_insufficient_material)
    DrawKind.FiftyMoveRule -> context.getString(R.string.draw_fifty_move_rule)
    DrawKind.SeventyFiveMoveRule -> context.getString(R.string.draw_seventy_five_move_rule)
    DrawKind.ThreefoldRepetition -> context.getString(R.string.draw_threefold_repetition)
    DrawKind.FivefoldRepetition -> context.getString(R.string.draw_fivefold_repetition)
    // The rule is unnamed, but it was still a draw - which is why the kind nests inside the
    // termination rather than replacing it.
    is DrawKind.Other -> context.getString(R.string.draw_unspecified)
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
