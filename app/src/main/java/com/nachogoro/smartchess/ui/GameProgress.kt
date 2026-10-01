package com.nachogoro.smartchess.ui

import scp.protocol.Clocks
import scp.protocol.Color
import scp.protocol.GameMode
import scp.protocol.GameStartedInfo
import scp.protocol.MovePlayedInfo
import scp.protocol.TimeControl
import scp.protocol.TimeControlType

/**
 * The game as the orchestrator has described it: how it started, and every move since.
 *
 * Built from `ui.onGameStarted` and `ui.onMovePlayed` alone, which between them carry everything
 * the game screen shows. Whose turn it is, and whether that turn is played on this board, are
 * worked out here: the protocol sends neither, because both follow from what it does send.
 */
data class GameProgress(
    val gameId: String,
    val mode: GameMode,
    val playerColor: Color?,
    val timeControl: TimeControl?,
    val whiteName: String?,
    val blackName: String?,
    val fen: String,
    val clocks: Clocks?,
    val ended: Boolean = false,
) {
    /** The side to move, from the FEN's active colour field. */
    val turn: Color
        get() = if (fen.split(' ').getOrNull(1) == "b") Color.BLACK else Color.WHITE

    /**
     * Whether the move being waited for is one somebody makes on this board.
     *
     * Always so in a local game, where both players are sitting here, and otherwise only on the
     * player's own turn: the engine's or the remote opponent's arrives by itself.
     */
    val awaitsBoard: Boolean
        get() = !ended && (mode == GameMode.LOCAL || turn == playerColor)

    /**
     * After [move]. A move without clocks leaves them as they were, which is only ever the case in
     * an untimed game, where there are none to leave.
     */
    fun after(move: MovePlayedInfo): GameProgress =
        if (move.gameId != gameId) this else copy(fen = move.fen, clocks = move.clocks ?: clocks)

    companion object {
        fun startedBy(info: GameStartedInfo) = GameProgress(
            gameId = info.gameId,
            mode = info.mode,
            playerColor = info.playerColor,
            timeControl = info.timeControl,
            whiteName = info.whiteName,
            blackName = info.blackName,
            fen = info.initialFen,
            clocks = info.timeControl
                ?.takeIf { it.type != TimeControlType.NONE }
                ?.let { Clocks(it.initialMs, it.initialMs) },
        )
    }
}
