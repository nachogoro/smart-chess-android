package com.nachogoro.smartchess.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.BoardCapabilities
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardError
import scp.protocol.BoardErrorEvent
import scp.protocol.BoardModule
import scp.protocol.BoardState
import scp.protocol.Color
import scp.protocol.Result
import scp.protocol.SquareState

/**
 * SCP [BoardModule] backed by the physical board over BLE.
 *
 * This is the only module that differs between the desktop demo (which fakes the board with a
 * file) and this app. Everything upstream - move inference, rules, clocks - is unchanged.
 *
 * The board reports colour, not piece type, so the capability level is normally
 * [BoardCapabilityLevel.COLOR] and the orchestrator infers moves from colour transitions.
 */
class BleBoardModule(
    private val connection: BleBoardConnection,
) : BoardModule {

    private val _errors = MutableSharedFlow<BoardErrorEvent>(extraBufferCapacity = 8)
    override val errors: SharedFlow<BoardErrorEvent> = _errors

    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        // Read from the identified board rather than from a live link. The link is down between
        // moves by design, and what the board is capable of does not change while it idles.
        val caps = connection.capabilities
            ?: return Result.Failure(BoardError.Disconnected())

        return Result.Success(BoardCapabilities(caps.toCapabilityLevel()))
    }

    override suspend fun getState(): Result<BoardError, BoardState> {
        // Gate on knowing which board to talk to, not on the link being up: the read below
        // reconnects if needed.
        if (!connection.isBoardKnown) {
            return Result.Failure(BoardError.Disconnected())
        }

        val raw = try {
            connection.readBoardState()
        } catch (e: Exception) {
            val message = e.message ?: "Could not read the board"
            _errors.tryEmit(BoardErrorEvent("DISCONNECTED", message))
            return Result.Failure(BoardError.Disconnected(message))
        }

        val readings = when (val parsed = parseBoardState(raw)) {
            is PacketResult.Invalid -> {
                _errors.tryEmit(BoardErrorEvent("SENSOR_FAILURE", parsed.reason))
                return Result.Failure(BoardError.SensorFailure(parsed.reason))
            }

            is PacketResult.Ok -> parsed.value
        }

        // An unreadable square is not an empty square. Rather than let the orchestrator infer a
        // move from a sensor glitch, report the failure and name the offending squares so the
        // player can re-seat the pieces and try again.
        val unknown = readings.filterValues { it == SquareReading.UNKNOWN }.keys.sorted()
        if (unknown.isNotEmpty()) {
            val message = buildString {
                append("Could not read ")
                append(if (unknown.size == 1) "square " else "squares ")
                append(unknown.take(6).joinToString(", "))
                if (unknown.size > 6) append(" and ${unknown.size - 6} more")
                append(". Re-seat the pieces and try again.")
            }
            _errors.tryEmit(BoardErrorEvent("SENSOR_FAILURE", message))
            return Result.Failure(BoardError.SensorFailure(message))
        }

        val squares = readings.mapValues { (_, reading) ->
            when (reading) {
                SquareReading.EMPTY -> SquareState.Empty
                SquareReading.WHITE -> SquareState.OccupiedByColor(Color.WHITE)
                SquareReading.BLACK -> SquareState.OccupiedByColor(Color.BLACK)
                // Filtered out above; mapped defensively so the `when` stays exhaustive.
                SquareReading.UNKNOWN -> SquareState.Empty
            }
        }
        return Result.Success(BoardState(squares))
    }
}

/**
 * Maps the firmware's numeric capability level onto the SCP levels.
 *
 * Level 2 ("colour") is what the V1 firmware reports. Level 1 means bare occupancy; anything
 * higher is treated as full piece recognition.
 */
private fun BoardCapabilitiesPacket.toCapabilityLevel(): BoardCapabilityLevel =
    when (capabilityLevel) {
        1 -> BoardCapabilityLevel.OCCUPANCY
        2 -> BoardCapabilityLevel.COLOR
        else -> BoardCapabilityLevel.FULL
    }
