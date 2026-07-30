package com.nachogoro.smartchess.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import scp.protocol.DrawKind
import scp.protocol.GameSummary
import scp.protocol.Termination

/**
 * One finished game.
 *
 * The moves are stored as a JSON column rather than a related table. They are only ever read as a
 * whole, with their game and in order, so a relation would buy nothing and cost a join; the shape
 * of a game record really is "a header and a list".
 *
 * Each move keeps the position that followed it, which is what makes the move-by-move replay
 * possible without re-deriving anything through the rules engine.
 */
@Entity(tableName = "games")
data class GameRecord(
    @PrimaryKey val gameId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    val mode: String,
    @ColumnInfo(name = "white_name") val whiteName: String?,
    @ColumnInfo(name = "black_name") val blackName: String?,
    @ColumnInfo(name = "player_color") val playerColor: String?,
    val result: String,
    /**
     * How the game ended, kept as codes so a stored game can be re-worded later rather than being
     * frozen in the language it was played in.
     *
     * [authorityCode] and [terminationText] belong to the draw kind when [terminationCode] is
     * `DRAWN`, and to the termination itself otherwise; only the `OTHER` cases carry them.
     */
    val terminationCode: String,
    val drawKindCode: String? = null,
    val authorityCode: String? = null,
    val terminationText: String? = null,
    @ColumnInfo(name = "final_fen") val finalFen: String,
    @ColumnInfo(name = "time_control") val timeControl: String?,
    @ColumnInfo(name = "moves_json") val movesJson: String,
) {
    val moves: List<StoredMove> get() = decodeMoves(movesJson)

    /** The move list as stored: SAN for the score sheet, FEN for the replay. */
    data class StoredMove(val san: String, val uci: String, val fen: String)

    companion object {
        /**
         * Builds a record from the summary the orchestrator emits when a game ends.
         *
         * @param playedAt when the game finished; passed in rather than read here so the caller
         *   controls the clock and this stays testable.
         */
        fun from(summary: GameSummary, playedAt: Long): GameRecord = GameRecord(
            gameId = summary.gameId,
            playedAt = playedAt,
            mode = summary.mode.name,
            whiteName = summary.whiteName,
            blackName = summary.blackName,
            playerColor = summary.playerColor?.name,
            result = summary.result.protocolString,
            terminationCode = summary.termination.code,
            drawKindCode = (summary.termination as? Termination.Drawn)?.kind?.code,
            authorityCode = summary.termination.authorityCode(),
            terminationText = summary.termination.text(),
            finalFen = summary.finalFen,
            timeControl = summary.timeControl?.let { "${it.type.name}:${it.initialMs}:${it.incrementMs}" },
            movesJson = encodeMoves(
                summary.moves.map { StoredMove(san = it.san, uci = it.move, fen = it.fen) }
            ),
        )

        private fun encodeMoves(moves: List<StoredMove>): String {
            val array = JSONArray()
            for (move in moves) {
                array.put(
                    JSONObject()
                        .put("san", move.san)
                        .put("uci", move.uci)
                        .put("fen", move.fen)
                )
            }
            return array.toString()
        }

        private fun decodeMoves(raw: String): List<StoredMove> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                StoredMove(
                    san = entry.getString("san"),
                    uci = entry.optString("uci"),
                    fen = entry.getString("fen"),
                )
            }
            // A record that cannot be decoded should cost its own move list, not the history screen.
        }.getOrDefault(emptyList())
    }
}

@Dao
interface GameHistoryDao {
    /** Most recent first: the history screen is almost always read from the top. */
    @Query("SELECT * FROM games ORDER BY played_at DESC")
    fun observeAll(): Flow<List<GameRecord>>

    @Query("SELECT * FROM games WHERE gameId = :gameId")
    suspend fun find(gameId: String): GameRecord?

    // A game can be reported finished more than once - the adapters replay their last event - and
    // the second report is the same game, so replacing is right and duplicates are impossible.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: GameRecord)

    @Query("DELETE FROM games WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}

@Database(entities = [GameRecord::class], version = 2, exportSchema = false)
abstract class GameHistoryDatabase : RoomDatabase() {
    abstract fun games(): GameHistoryDao
}

/** The unrecognised half of a termination, wherever it sits. */
private fun Termination.authorityCode(): String? = when (this) {
    is Termination.Drawn -> (kind as? DrawKind.Other)?.authorityCode
    is Termination.Other -> authorityCode
    else -> null
}

private fun Termination.text(): String? = when (this) {
    is Termination.Drawn -> (kind as? DrawKind.Other)?.text
    is Termination.Other -> text
    else -> null
}

/**
 * Rebuilds the termination a record was written from.
 *
 * A code this build does not recognise becomes `Other`, which is the same thing the protocol asks
 * a UI to do with one it meets over the wire.
 */
fun GameRecord.termination(): Termination = when (terminationCode) {
    Termination.Checkmate.code -> Termination.Checkmate
    Termination.Stalemate.code -> Termination.Stalemate
    Termination.Resignation.code -> Termination.Resignation
    Termination.Timeout.code -> Termination.Timeout
    Termination.Aborted.code -> Termination.Aborted
    "DRAWN" -> Termination.Drawn(drawKind())
    else -> Termination.Other(authorityCode, terminationText)
}

private fun GameRecord.drawKind(): DrawKind = when (drawKindCode) {
    DrawKind.Agreement.code -> DrawKind.Agreement
    DrawKind.InsufficientMaterial.code -> DrawKind.InsufficientMaterial
    DrawKind.FiftyMoveRule.code -> DrawKind.FiftyMoveRule
    DrawKind.SeventyFiveMoveRule.code -> DrawKind.SeventyFiveMoveRule
    DrawKind.ThreefoldRepetition.code -> DrawKind.ThreefoldRepetition
    DrawKind.FivefoldRepetition.code -> DrawKind.FivefoldRepetition
    else -> DrawKind.Other(authorityCode, terminationText)
}
