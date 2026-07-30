package com.nachogoro.smartchess.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import scp.protocol.GameSummary

/**
 * Stores finished games.
 *
 * Games are recorded as soon as the orchestrator reports one finished, not when the user dismisses
 * the result dialog, so a game survives the app being killed at the board.
 */
class GameHistoryRepository(context: Context) {

    private val database = Room
        .databaseBuilder(
            context.applicationContext,
            GameHistoryDatabase::class.java,
            DATABASE_NAME,
        )
        // Pre-release: a schema change drops the saved games rather than carrying a migration for
        // history nobody depends on yet.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private val dao = database.games()

    val games: Flow<List<GameRecord>> = dao.observeAll()

    suspend fun record(summary: GameSummary, playedAt: Long = System.currentTimeMillis()) {
        // A game with no moves is an abandoned setup, not a game worth keeping.
        if (summary.moves.isEmpty()) return
        dao.insert(GameRecord.from(summary, playedAt))
    }

    suspend fun find(gameId: String): GameRecord? = dao.find(gameId)

    suspend fun delete(gameId: String) = dao.delete(gameId)

    private companion object {
        const val DATABASE_NAME = "game_history.db"
    }
}
