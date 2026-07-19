package com.nachogoro.smartchess.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a stored game as PGN.
 *
 * The output targets the export format in the PGN standard: the seven mandatory tags in their
 * required order, a blank line, then the movetext wrapped at 80 columns and terminated by the
 * result. That is what other chess software expects to be able to import, which is the whole point
 * of offering PGN at all.
 */
fun GameRecord.toPgn(): String {
    val tags = buildList {
        add("Event" to "Smart Chess ${mode.lowercase(Locale.ROOT)} game")
        add("Site" to "Smart Chess board")
        add("Date" to PGN_DATE_FORMAT.format(Date(playedAt)))
        add("Round" to "-")
        add("White" to (whiteName ?: "?"))
        add("Black" to (blackName ?: "?"))
        add("Result" to result.ifBlank { "*" })

        // Optional tags, only when they carry something.
        timeControl?.let { add("TimeControl" to it.toPgnTimeControl()) }
        if (reason.isNotBlank()) add("Termination" to reason)
    }

    val header = tags.joinToString("\n") { (name, value) ->
        // Backslashes and quotes are the only characters that need escaping inside a tag value.
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        "[$name \"$escaped\"]"
    }

    return "$header\n\n${movetext()}"
}

/** `1. e4 e5 2. Nf3 Nc6 ... 1-0`, wrapped so no line exceeds 80 characters. */
private fun GameRecord.movetext(): String {
    val tokens = buildList {
        moves.forEachIndexed { index, move ->
            // Full moves are numbered; the number precedes White's move, which is every even index.
            if (index % 2 == 0) add("${index / 2 + 1}.")
            add(move.san)
        }
        add(result.ifBlank { "*" })
    }

    val lines = mutableListOf<String>()
    var line = StringBuilder()
    for (token in tokens) {
        // +1 for the space that would join them.
        if (line.isNotEmpty() && line.length + 1 + token.length > MAX_LINE_LENGTH) {
            lines.add(line.toString())
            line = StringBuilder()
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(token)
    }
    if (line.isNotEmpty()) lines.add(line.toString())

    return lines.joinToString("\n")
}

/**
 * Converts the stored `TYPE:initialMs:incrementMs` triple into PGN's `seconds+increment`.
 *
 * PGN has no way to express Bronstein or simple delay in this tag, so they are written as if they
 * were Fischer increment - the initial time and increment are still correct, which is what a reader
 * uses the tag for.
 */
private fun String.toPgnTimeControl(): String {
    val parts = split(':')
    if (parts.size != 3) return "?"
    val initialMs = parts[1].toLongOrNull() ?: return "?"
    val incrementMs = parts[2].toLongOrNull() ?: 0L
    return "${initialMs / 1000}+${incrementMs / 1000}"
}

private const val MAX_LINE_LENGTH = 80

// PGN dates are yyyy.mm.dd. Locale.ROOT keeps the digits Western regardless of device locale.
private val PGN_DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT)
