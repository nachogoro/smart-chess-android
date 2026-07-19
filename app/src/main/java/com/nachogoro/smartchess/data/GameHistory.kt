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
import scp.protocol.GameSummary

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
    val reason: String,
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
            result = summary.result,
            reason = summary.reason,
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

@Database(entities = [GameRecord::class], version = 1, exportSchema = false)
abstract class GameHistoryDatabase : RoomDatabase() {
    abstract fun games(): GameHistoryDao
}
