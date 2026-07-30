package com.nachogoro.smartchess.ui

import android.content.Context
import com.nachogoro.smartchess.R
import scp.protocol.BoardError
import scp.protocol.ModuleError
import scp.protocol.UIMessage

/**
 * A message from the orchestrator, in words.
 *
 * The wording lives here because the UI module is the only one that knows who is reading.
 */
fun UIMessage.describe(context: Context): String = when (this) {
    is UIMessage.BoardReadFailed -> context.readFailureText(error)
    UIMessage.BoardUnchanged -> context.getString(R.string.msg_board_unchanged)
    UIMessage.NoLegalMoveMatches -> context.getString(R.string.msg_no_legal_move_matches)
    UIMessage.EngineCapabilitiesUnavailable ->
        context.getString(R.string.msg_engine_capabilities_unavailable)
    UIMessage.RemoteCapabilitiesUnavailable ->
        context.getString(R.string.msg_remote_capabilities_unavailable)
    UIMessage.DrawNotSupportedByEngine -> context.getString(R.string.msg_draw_not_supported)

    is UIMessage.BoardFailed -> context.withDetail(R.string.msg_board_failed, error)
    is UIMessage.BoardCapabilitiesUnavailable ->
        context.withDetail(R.string.msg_board_capabilities_unavailable, error)
    is UIMessage.EngineConfigurationFailed ->
        context.withDetail(R.string.msg_engine_configuration_failed, error)
    is UIMessage.RemoteConfigurationFailed ->
        context.withDetail(R.string.msg_remote_configuration_failed, error)
    is UIMessage.SeekFailed -> context.withDetail(R.string.msg_seek_failed, error)
    is UIMessage.MoveRejected -> context.withDetail(R.string.msg_move_rejected, error)
    is UIMessage.OpponentMoveRejected ->
        context.withDetail(R.string.msg_opponent_move_rejected, error)
    is UIMessage.EngineMoveRejected -> context.withDetail(R.string.msg_engine_move_rejected, error)
    is UIMessage.MoveNotSent -> context.withDetail(R.string.msg_move_not_sent, error)
    is UIMessage.EngineFailed -> context.withDetail(R.string.msg_engine_failed, error)
    is UIMessage.RemoteFailed -> context.withDetail(R.string.msg_remote_failed, error)
    is UIMessage.GameCreationFailed -> context.withDetail(R.string.msg_game_creation_failed, error)

    // The diagnostic is deliberately absent: it is a Kotlin exception message, which belongs in
    // the log and not in front of a player.
    is UIMessage.UnexpectedFailure -> context.getString(R.string.msg_unexpected_failure)
    // Nothing here recognises this one, so whatever wording came with it beats inventing some -
    // and it came from outside, so it is not ours to translate either.
    is UIMessage.Other -> text ?: authorityCode ?: context.getString(R.string.msg_unexpected_failure)
}

/**
 * This app's wording, with the failing module's code appended.
 *
 * The code rather than the module's `diagnostic`: that field is developer-facing and is
 * English written elsewhere, so showing it would put a fragment in front of the player that no
 * translation of this app could reach. A code at least means something in a bug report.
 */
private fun Context.withDetail(wording: Int, error: ModuleError): String =
    getString(R.string.msg_with_detail, getString(wording), error.code)

/**
 * A failed board read, said as usefully as the error allows.
 *
 * A sensor failure knows which squares it could not trust, and naming them tells the player where
 * to look, which is the whole reason the board module reports them as a list rather than keeping
 * the sentence to itself.
 */
private fun Context.readFailureText(error: ModuleError): String {
    val squares = (error as? BoardError.SensorFailure)?.unreadableSquares.orEmpty()
    return when {
        squares.isEmpty() -> withDetail(R.string.msg_board_read_failed, error)
        squares.size == 1 -> getString(R.string.msg_unreadable_square, squares.single())
        // Enough to act on without becoming a wall of coordinates.
        squares.size <= MAX_SQUARES_LISTED ->
            getString(R.string.msg_unreadable_squares, squares.joinToString(", "))
        else -> getString(
            R.string.msg_unreadable_squares_truncated,
            squares.size,
            squares.take(MAX_SQUARES_LISTED).joinToString(", "),
        )
    }
}

private const val MAX_SQUARES_LISTED = 6
