package com.nachogoro.smartchess.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-line rendering that goes to logcat on every board read.
 *
 * Both packets below were captured from the physical board rather than constructed, so this also
 * pins the wire format the app decodes: a diagnostic that reads plausibly but decodes wrongly
 * would mislead at exactly the moment it is being relied on.
 */
class BoardPlacementTest {

    private fun placementOf(hex: String): String {
        val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val parsed = parseBoardState(bytes)
        return (parsed as PacketResult.Ok).value.toPlacement()
    }

    @Test
    fun `starting position`() {
        assertEquals(
            "bbbbbbbb/bbbbbbbb/8/8/8/8/WWWWWWWW/WWWWWWWW",
            placementOf("01 01 AA AA AA AA 00 00 00 00 00 00 00 00 55 55 55 55"),
        )
    }

    @Test
    fun `midgame position`() {
        // The board's scripted game after 12...Ke8: black king back on e8, white queen on c7,
        // black queen deep on g2.
        assertEquals(
            "b3bbbb/bbW2b1b/4b3/4W3/2W1b3/8/WWW2WbW/WWW1W2W",
            placementOf("01 01 02 AA 1A 88 00 02 00 01 10 02 00 00 15 64 15 41"),
        )
    }
}
