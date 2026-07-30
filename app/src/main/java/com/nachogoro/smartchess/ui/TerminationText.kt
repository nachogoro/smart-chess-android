package com.nachogoro.smartchess.ui

import scp.protocol.DrawKind
import scp.protocol.Termination

/**
 * How a game ended, in words.
 *
 * The wording lives here because the UI module is the only one allowed to write it: every other
 * SCP module reports a code, since none of them can know who is reading (PROTOCOL.md §2.6). These
 * are still English literals like the rest of the app's text; what has changed is that choosing
 * them is now this layer's job, which is what makes translating them possible later.
 */
fun Termination.describe(): String = when (this) {
    Termination.Checkmate -> "Checkmate"
    Termination.Stalemate -> "Stalemate"
    Termination.Resignation -> "Resignation"
    Termination.Timeout -> "Out of time"
    Termination.Aborted -> "Aborted"
    is Termination.Drawn -> kind.describe()
    // Whoever ended the game named something this build does not know. Their own words, or failing
    // that their identifier, beats inventing a reason for them.
    is Termination.Other -> text ?: authorityCode ?: "Unknown"
}

private fun DrawKind.describe(): String = when (this) {
    DrawKind.Agreement -> "Draw by agreement"
    DrawKind.InsufficientMaterial -> "Draw, insufficient material"
    DrawKind.FiftyMoveRule -> "Draw, fifty-move rule"
    DrawKind.SeventyFiveMoveRule -> "Draw, 75-move rule"
    DrawKind.ThreefoldRepetition -> "Draw, threefold repetition"
    DrawKind.FivefoldRepetition -> "Draw, fivefold repetition"
    // The rule is unnamed, but it was still a draw - which is why the kind nests inside the
    // termination rather than replacing it.
    is DrawKind.Other -> "Draw"
}
