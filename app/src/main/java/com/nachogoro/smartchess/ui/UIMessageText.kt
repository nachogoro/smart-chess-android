package com.nachogoro.smartchess.ui

import scp.protocol.ModuleError
import scp.protocol.UIMessage

/**
 * A message from the orchestrator, in words.
 *
 * The wording lives here because the UI module is the only one that knows who is reading
 * (PROTOCOL.md §2.6). These are still English literals like the rest of the app's text; what
 * changed is that choosing them is now this layer's job.
 */
fun UIMessage.describe(): String = when (this) {
    UIMessage.BoardReadFailed -> "Could not read the board."
    UIMessage.BoardUnchanged -> "The board has not changed. Move a piece, then tap again."
    UIMessage.NoLegalMoveMatches -> "The board does not match any legal move."
    UIMessage.EngineCapabilitiesUnavailable -> "Could not read the engine's capabilities."
    UIMessage.RemoteCapabilitiesUnavailable -> "Could not read the platform's capabilities."
    UIMessage.DrawNotSupportedByEngine -> "The engine does not take draw offers."
    is UIMessage.BoardFailed -> "Board error." withDetail error
    is UIMessage.MoveRejected -> "That move was rejected." withDetail error
    is UIMessage.OpponentMoveRejected -> "The opponent's move was rejected." withDetail error
    is UIMessage.EngineMoveRejected -> "The engine's move was rejected." withDetail error
    is UIMessage.MoveNotSent -> "Your move could not be sent to the server." withDetail error
    is UIMessage.EngineFailed -> "Engine error." withDetail error
    is UIMessage.RemoteFailed -> "Remote error." withDetail error
    is UIMessage.GameCreationFailed -> "The game could not be started." withDetail error
    // The diagnostic is deliberately absent: it is a Kotlin exception message, which belongs in
    // the log and not in front of a player.
    is UIMessage.UnexpectedFailure -> "Something went wrong."
    // Nothing here recognises this one, so whatever wording came with it beats inventing some.
    is UIMessage.Other -> text ?: authorityCode ?: "Something went wrong."
}

/**
 * The failing module's code, as detail beside this app's own wording.
 *
 * The code rather than the module's `message`: that field is developer-facing (§2.6) and is
 * English written elsewhere, so showing it would put a fragment in front of the player that no
 * translation of this app could ever reach. The code at least means something to whoever is
 * reading a bug report.
 */
private infix fun String.withDetail(error: ModuleError) = "$this (${error.code})"
