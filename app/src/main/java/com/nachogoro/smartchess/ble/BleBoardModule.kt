package com.nachogoro.smartchess.ble

import android.util.Log
import com.nachogoro.smartchess.CHESS_LOG_TAG
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.BoardCapabilities
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardError
import scp.protocol.BoardModule
import scp.protocol.BoardState
import scp.protocol.Color
import scp.protocol.Result
import scp.protocol.SquareState

/**
 * SCP [BoardModule] backed by the physical board over BLE.
 *
 * The board reports colour, not piece type, so the capability level is normally
 * [BoardCapabilityLevel.COLOR] and the orchestrator infers moves from colour transitions.
 */
class BleBoardModule(
    private val connection: BleBoardConnection,
) : BoardModule {

    private val _errors = MutableSharedFlow<BoardError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<BoardError> = _errors

    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        // Read from the cache, as it does not change through the games.
        val caps = connection.capabilities
            ?: return Result.Failure(BoardError.Disconnected())

        return Result.Success(BoardCapabilities(caps.toCapabilityLevel()))
    }

    override suspend fun getState(): Result<BoardError, BoardState> {
        if (!connection.isBoardKnown) {
            return Result.Failure(BoardError.Disconnected())
        }

        val raw = try {
            connection.readBoardState()
        } catch (e: Exception) {
            val message = e.message ?: "Could not read the board"
            Log.w(CHESS_LOG_TAG, "Board read failed: $message")
            val error = BoardError.Disconnected(diagnostic = message)
            _errors.tryEmit(error)
            return Result.Failure(error)
        }

        val readings = when (val parsed = parseBoardState(raw)) {
            is PacketResult.Invalid -> {
                Log.w(CHESS_LOG_TAG, "Board packet rejected: ${parsed.reason}")
                val error = BoardError.SensorFailure(diagnostic = parsed.reason)
                _errors.tryEmit(error)
                return Result.Failure(error)
            }

            is PacketResult.Ok -> parsed.value
        }

        Log.i(CHESS_LOG_TAG, "Board reports ${readings.toPlacement()}")

        // An unreadable square is not an empty square. Rather than let the orchestrator infer a
        // move from a sensor glitch, report the failure and name the offending squares so the UI
        // can tell the player which pieces to re-seat.
        val unknown = readings.filterValues { it == SquareReading.UNKNOWN }.keys.sorted()
        if (unknown.isNotEmpty()) {
            Log.w(CHESS_LOG_TAG, "Board reading rejected, unreadable squares: ${unknown.joinToString(", ")}")
            val error = BoardError.SensorFailure(unreadableSquares = unknown)
            _errors.tryEmit(error)
            return Result.Failure(error)
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
 * The reading as a single line, in FEN's placement style: ranks 8 down to 1, `/` between them,
 * runs of empty squares as a count.
 *
 * `W` and `b` rather than piece letters, because colour is all the board reports, and `?` for a
 * square it could not read.
 */
internal fun Map<String, SquareReading>.toPlacement(): String =
    (8 downTo 1).joinToString("/") { rank ->
        buildString {
            var empty = 0
            for (file in 'a'..'h') {
                val symbol = when (this@toPlacement["$file$rank"]) {
                    SquareReading.WHITE -> 'W'
                    SquareReading.BLACK -> 'b'
                    SquareReading.UNKNOWN -> '?'
                    else -> null
                }
                if (symbol == null) {
                    empty++
                } else {
                    if (empty > 0) append(empty).also { empty = 0 }
                    append(symbol)
                }
            }
            if (empty > 0) append(empty)
        }
    }

/**
 * Maps the firmware's numeric capability level onto the SCP levels.
 */
private fun BoardCapabilitiesPacket.toCapabilityLevel(): BoardCapabilityLevel =
    when (capabilityLevel) {
        1 -> BoardCapabilityLevel.OCCUPANCY
        2 -> BoardCapabilityLevel.COLOR
        else -> BoardCapabilityLevel.FULL
    }
