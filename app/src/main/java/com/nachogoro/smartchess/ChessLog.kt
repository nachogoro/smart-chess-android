package com.nachogoro.smartchess

/**
 * Logcat tag for the running account of a game: what the board reported, and what the app made of
 * it.
 *
 * One tag across the layers deliberately, so a whole session comes out of
 * `adb logcat -s ChessGame BleBoard` in order. That pairing is what tells a board that is
 * misreporting apart from an app that is misreading, and neither half answers it alone: `BleBoard`
 * carries the raw bytes, this carries what they decoded to and what was concluded from them.
 */
internal const val CHESS_LOG_TAG = "ChessGame"
