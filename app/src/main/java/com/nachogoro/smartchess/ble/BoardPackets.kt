package com.nachogoro.smartchess.ble

import java.util.UUID

/**
 * Wire format of the BLE chess board, protocol V1.
 *
 * The board is a passive sensor: it reports the observed state of the 64 squares and nothing
 * else. It does not know the rules, does not validate moves, and does not distinguish piece
 * types, only colour. All game logic lives in this app.
 *
 * See `GUIA_APP_MOVIL_BLE_CHESSBOARD_V1-0-0_ES.md` for the normative description.
 */
object BoardBle {
    val SERVICE_UUID: UUID = UUID.fromString("7b6a0001-9f3d-4c2a-8a1e-2f6b8c9d1000")
    val BOARD_STATE_UUID: UUID = UUID.fromString("7b6a0002-9f3d-4c2a-8a1e-2f6b8c9d1000")
    val CAPABILITIES_UUID: UUID = UUID.fromString("7b6a0003-9f3d-4c2a-8a1e-2f6b8c9d1000")
    val STATUS_UUID: UUID = UUID.fromString("7b6a0004-9f3d-4c2a-8a1e-2f6b8c9d1000")

    /** Client Characteristic Configuration Descriptor, for enabling notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION = 0x01

    const val TYPE_BOARD_STATE = 0x01
    const val TYPE_STATUS = 0x02
    const val TYPE_CAPABILITIES = 0x10

    const val BOARD_STATE_LENGTH = 18

    /**
     * Size of the Capabilities header this app reads. A board may send a longer packet; the extra
     * bytes are not ours to interpret.
     */
    const val CAPABILITIES_MIN_LENGTH = 10
}

/**
 * What the board reports for a single square.
 *
 * [UNKNOWN] is deliberately distinct from [EMPTY]: it means the sensor reading was not
 * trustworthy, not that the square is vacant. Collapsing the two would let a flaky sensor be
 * silently interpreted as a lifted piece, which is how phantom moves get inferred.
 */
enum class SquareReading(val raw: Int) {
    EMPTY(0),
    WHITE(1),
    BLACK(2),
    UNKNOWN(3);

    companion object {
        fun fromRaw(value: Int): SquareReading =
            entries.firstOrNull { it.raw == value } ?: UNKNOWN
    }
}

/** Result of parsing a packet: either a value or a human-readable reason it was rejected. */
sealed interface PacketResult<out T> {
    data class Ok<T>(val value: T) : PacketResult<T>
    data class Invalid(val reason: String) : PacketResult<Nothing>
}

/** Decoded `Capabilities` packet. */
data class BoardCapabilitiesPacket(
    val protocolVersion: Int,
    val capabilityLevel: Int,
    val rows: Int,
    val columns: Int,
    val boardStatePacketLength: Int,
    val bitsPerSquare: Int,
    val supportsRead: Boolean,
    val supportsNotify: Boolean,
    val simulationMode: Boolean,
)

/** Decoded `Status` packet (16 bytes). Every field is optional in practice. */
data class BoardStatusPacket(
    val boardReady: Boolean,
    val operatingMode: Int,
    val bleSecurity: Boolean,
    val lastErrorCode: Int,
    val batteryPercent: Int?,
    val temperatureCelsius: Int?,
) {
    val lastErrorLabel: String
        get() = when (lastErrorCode) {
            0 -> "OK"
            1 -> "Board not ready"
            2 -> "Sensor failure"
            3 -> "Board processor disconnected"
            4 -> "Battery low"
            5 -> "Temperature high"
            6 -> "Internal error"
            255 -> "Unknown"
            else -> "Error code $lastErrorCode"
        }
}

/**
 * Parses an 18-byte `BoardState` packet into a map of algebraic square name to reading.
 *
 * Square order on the wire is a8, b8 ... h8, a7 ... h1 - that is, the packet starts at rank 8
 * (Black's back rank) and ends at rank 1. Each byte holds four squares, two bits each, least
 * significant bits first.
 */
fun parseBoardState(packet: ByteArray): PacketResult<Map<String, SquareReading>> {
    if (packet.size != BoardBle.BOARD_STATE_LENGTH) {
        return PacketResult.Invalid("Expected ${BoardBle.BOARD_STATE_LENGTH} bytes, got ${packet.size}")
    }
    val type = packet[0].toInt() and 0xFF
    if (type != BoardBle.TYPE_BOARD_STATE) {
        return PacketResult.Invalid("Not a BoardState packet (type=0x%02X)".format(type))
    }
    val version = packet[1].toInt() and 0xFF
    if (version != BoardBle.PROTOCOL_VERSION) {
        return PacketResult.Invalid("Unsupported protocol version $version")
    }

    val squares = HashMap<String, SquareReading>(64)
    for (i in 0 until 64) {
        val packedByte = packet[2 + i / 4].toInt() and 0xFF
        val shift = (i % 4) * 2
        val value = (packedByte shr shift) and 0x03

        val row = i / 8 // 0 == rank 8
        val col = i % 8 // 0 == file 'a'
        squares[squareName(row, col)] = SquareReading.fromRaw(value)
    }
    return PacketResult.Ok(squares)
}

/**
 * Parses a `Capabilities` packet and validates the fields this app depends on.
 *
 * Only the first [BoardBle.CAPABILITIES_MIN_LENGTH] bytes are read, and a longer packet is
 * accepted: compatibility is decided by the protocol version and the field checks below, so a
 * firmware that appends its own fields to the header is still usable. A board that genuinely does
 * not fit is rejected on content, which says more than a byte count would.
 */
fun parseCapabilities(packet: ByteArray): PacketResult<BoardCapabilitiesPacket> {
    if (packet.size < BoardBle.CAPABILITIES_MIN_LENGTH) {
        return PacketResult.Invalid(
            "Capabilities packet too short (${packet.size} bytes, need at least " +
                "${BoardBle.CAPABILITIES_MIN_LENGTH})"
        )
    }
    val type = packet[0].toInt() and 0xFF
    if (type != BoardBle.TYPE_CAPABILITIES) {
        return PacketResult.Invalid("Not a Capabilities packet (type=0x%02X)".format(type))
    }
    val version = packet[1].toInt() and 0xFF
    if (version != BoardBle.PROTOCOL_VERSION) {
        return PacketResult.Invalid("Unsupported protocol version $version")
    }

    val parsed = BoardCapabilitiesPacket(
        protocolVersion = version,
        capabilityLevel = packet[2].toInt() and 0xFF,
        rows = packet[3].toInt() and 0xFF,
        columns = packet[4].toInt() and 0xFF,
        boardStatePacketLength = packet[5].toInt() and 0xFF,
        bitsPerSquare = packet[6].toInt() and 0xFF,
        supportsRead = (packet[7].toInt() and 0xFF) != 0,
        supportsNotify = (packet[8].toInt() and 0xFF) != 0,
        simulationMode = (packet[9].toInt() and 0xFF) != 0,
    )

    if (parsed.rows != 8 || parsed.columns != 8) {
        return PacketResult.Invalid("Unsupported board size ${parsed.rows}x${parsed.columns}")
    }
    if (parsed.boardStatePacketLength != BoardBle.BOARD_STATE_LENGTH) {
        return PacketResult.Invalid("Unexpected BoardState length ${parsed.boardStatePacketLength}")
    }
    if (parsed.bitsPerSquare != 2) {
        return PacketResult.Invalid("Unsupported encoding: ${parsed.bitsPerSquare} bits per square")
    }
    return PacketResult.Ok(parsed)
}

/** Parses a `Status` packet. Tolerates packets longer than 16 bytes (reserved growth). */
fun parseStatus(packet: ByteArray): PacketResult<BoardStatusPacket> {
    if (packet.size < 12) {
        return PacketResult.Invalid("Status packet too short (${packet.size} bytes)")
    }
    val type = packet[0].toInt() and 0xFF
    if (type != BoardBle.TYPE_STATUS) {
        return PacketResult.Invalid("Not a Status packet (type=0x%02X)".format(type))
    }

    fun byteOrNull(index: Int, sentinel: Int = 255): Int? =
        (packet[index].toInt() and 0xFF).takeIf { it != sentinel }

    return PacketResult.Ok(
        BoardStatusPacket(
            boardReady = (packet[3].toInt() and 0xFF) == 1,
            operatingMode = packet[4].toInt() and 0xFF,
            bleSecurity = (packet[7].toInt() and 0xFF) == 1,
            lastErrorCode = packet[8].toInt() and 0xFF,
            batteryPercent = byteOrNull(10),
            temperatureCelsius = byteOrNull(11),
        )
    )
}

/** Converts a (row, col) pair - row 0 being rank 8 - to an algebraic square name such as "e4". */
private fun squareName(row: Int, col: Int): String {
    val file = 'a' + col
    val rank = 8 - row
    return "$file$rank"
}
