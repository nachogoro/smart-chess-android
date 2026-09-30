package com.nachogoro.smartchess.core

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nachogoro.smartchess.CHESS_LOG_TAG
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BleBoardModule
import com.nachogoro.smartchess.ble.BoardConnectionState
import com.nachogoro.smartchess.ble.PacketResult
import com.nachogoro.smartchess.ble.SquareReading
import com.nachogoro.smartchess.ble.parseBoardState
import com.nachogoro.smartchess.data.AppSettings
import com.nachogoro.smartchess.data.GameHistoryRepository
import com.nachogoro.smartchess.data.GameRecord
import com.nachogoro.smartchess.data.SettingsRepository
import com.nachogoro.smartchess.speech.MoveSpeaker
import com.nachogoro.smartchess.ui.AndroidUIModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import scp.engine.carballo.CarballoAdapter
import scp.engine.remote.RemoteStockfishAdapter
import scp.game.GameController
import scp.remote.lichess.LichessAdapter
import scp.orchestrator.GameSession
import scp.orchestrator.Orchestrator
import scp.orchestrator.OrchestratorState
import scp.protocol.Color
import scp.protocol.EngineAdapterModule
import scp.protocol.GameMode
import scp.protocol.RemoteAdapterModule

/** Top-level destinations. Deliberately a plain enum - the flow is small and mostly linear. */
enum class Screen {
    CONNECTION,
    MAIN_MENU,
    SETTINGS,
    GAME_SETUP,
    GAME,
    HISTORY,
    BOARD_DEBUG,
}

/**
 * One raw read of the board, for the debug screen.
 *
 * Keeps the bytes as they arrived beside what they decoded to, because checking the second
 * against the first is the whole point of that screen. [index] counts the reads of this session:
 * two consecutive readings of an untouched board are otherwise indistinguishable, and telling
 * "the read did nothing" from "the board did not change" is exactly what is being verified.
 */
data class BoardReading(
    val index: Int,
    val bytes: ByteArray,
    val squares: Map<String, SquareReading>?,
    /** Why the read or the decode failed, in the words of whatever rejected it. Null on success. */
    val error: String?,
)

/**
 * Owns the SCP stack for the lifetime of the app.
 *
 * The orchestrator's event loop runs in [viewModelScope], so it survives configuration changes and
 * is torn down with the ViewModel. Everything the UI needs is mirrored into observable state here.
 */
class ChessViewModel(application: Application) : AndroidViewModel(application) {

    val uiModule = AndroidUIModule()

    val connection = BleBoardConnection(application)
    private val board = BleBoardModule(connection)

    /**
     * Scope for the SCP modules themselves.
     *
     * Separate from [viewModelScope] because the game controller schedules clock timers on it and
     * must not be cancelled by UI lifecycle churn.
     */
    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val gameController = GameController(moduleScope)

    /**
     * Engine and remote adapters that are compiled into this build.
     *
     * The main menu is derived from these, so a build without an engine adapter simply does not
     * offer engine play.
     */
    private val engines: List<EngineAdapterModule> =
        listOf(CarballoAdapter(), RemoteStockfishAdapter())
    private val remotes: List<RemoteAdapterModule> = listOf(LichessAdapter(moduleScope))

    private val orchestrator = Orchestrator(
        board = board,
        gameController = gameController,
        ui = uiModule,
        engines = engines,
        remotes = remotes,
    )

    private val historyRepository = GameHistoryRepository(application)
    val playedGames: StateFlow<List<GameRecord>> = historyRepository.games
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val settingsRepository = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val speaker = MoveSpeaker(application)

    val connectionState: StateFlow<BoardConnectionState> = connection.state

    private val _screen = MutableStateFlow(Screen.CONNECTION)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _setupMode = MutableStateFlow<GameMode?>(null)
    val setupMode: StateFlow<GameMode?> = _setupMode.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _boardReading = MutableStateFlow<BoardReading?>(null)
    val boardReading: StateFlow<BoardReading?> = _boardReading.asStateFlow()

    private val _isReadingBoard = MutableStateFlow(false)
    val isReadingBoard: StateFlow<Boolean> = _isReadingBoard.asStateFlow()

    val orchestratorState: StateFlow<OrchestratorState> = orchestrator.stateFlow
    val session: StateFlow<GameSession?> = orchestrator.sessionFlow

    val availableModes: List<GameMode> = buildList {
        add(GameMode.LOCAL)
        if (engines.isNotEmpty()) add(GameMode.ENGINE)
        if (remotes.isNotEmpty()) add(GameMode.REMOTE)
    }

    init {
        viewModelScope.launch(Dispatchers.Default) { orchestrator.start() }

        // Record a game the moment the orchestrator reports it finished, rather than when the user
        // dismisses the result dialog: otherwise a game is lost if the app dies at the board.
        // Re-recording the same game is harmless, as records are keyed by game id and replaced.
        viewModelScope.launch {
            uiModule.gameEnded.collect { historyRepository.record(it) }
        }

        viewModelScope.launch {
            session.mapNotNull { it?.fen }.collect { uiModule.onPositionChanged(it) }
        }

        // Announced from here rather than from the game screen (the move happened, regardless of screen).
        viewModelScope.launch {
            uiModule.opponentMoved.collect { move ->
                if (settings.value.announceOpponentMoves) speaker.announce(move)
            }
        }
    }

    // -- Connection --

    fun connectToBoard() {
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            try {
                val result = connection.connect()
                if (result.isSuccess && _screen.value == Screen.CONNECTION) {
                    _screen.value = Screen.MAIN_MENU
                }
            } finally {
                _isConnecting.value = false
            }
        }
    }

    /** Lets the user proceed to the menu without a board - useful for browsing past games. */
    fun continueWithoutBoard() {
        _screen.value = Screen.MAIN_MENU
    }

    // -- Navigation --

    fun openSettings() { _screen.value = Screen.SETTINGS }
    fun openHistory() { _screen.value = Screen.HISTORY }
    fun openBoardDebug() { _screen.value = Screen.BOARD_DEBUG }

    fun deleteGame(gameId: String) {
        viewModelScope.launch { historyRepository.delete(gameId) }
    }
    fun backToMenu() { _screen.value = Screen.MAIN_MENU }

    fun startGame(mode: GameMode) {
        _setupMode.value = mode
        _screen.value = Screen.GAME_SETUP
        // A withdrawn seek leaves the orchestrator already asking which mode to play. Answering
        // that outstanding prompt is enough; emitting again would start a second setup flow
        // alongside the first. The setup screen answers it as soon as it composes.
        if (uiModule.pendingGameModeSelection == null) {
            viewModelScope.launch { uiModule.requestNewGame() }
        }
    }

    /**
     * Withdraws a seek that is waiting for a remote opponent.
     *
     * Cancelling the pending UI requests is not enough here: by the time the app is seeking, the
     * orchestrator has already returned from its setup flow and is waiting on the platform, so
     * there is no prompt left to cancel and the seek would stay live on the server.
     *
     * Re-requesting a new game is the orchestrator's documented escape hatch: it withdraws the
     * seek, tears the session down and returns to idle. It then immediately asks which mode to
     * play again, which [startGame] answers rather than duplicating.
     */
    fun cancelSeek() {
        uiModule.cancelAllPendingRequests()
        viewModelScope.launch { uiModule.requestNewGame() }
        _setupMode.value = null
        _screen.value = Screen.MAIN_MENU
    }

    fun cancelSetup() {
        uiModule.cancelAllPendingRequests()
        _setupMode.value = null
        _screen.value = Screen.MAIN_MENU
    }

    fun onGameStarted() {
        uiModule.clearGameStartedInfo()
        _screen.value = Screen.GAME
    }

    fun onGameFinished() {
        uiModule.clearGameSummary()
        uiModule.clearOpponentMove()
        _setupMode.value = null
        _screen.value = Screen.MAIN_MENU
    }

    // -- In-game actions --

    /**
     * The player has finished moving on the physical board.
     */
    fun signalMoveReady() {
        val session = this.session.value ?: return
        val color = requesterColor(session) ?: return
        Log.i(CHESS_LOG_TAG, "Move ready; expecting one move from ${session.fen}")
        viewModelScope.launch { uiModule.signalMoveReady(session.gameId, color) }
    }

    /**
     * [color] resigns. Each player's half of the game screen has its own Resign button, so which
     * one was pressed says who is giving up, whoever is to move; the orchestrator resigns exactly
     * the colour it is given.
     */
    fun resign(color: Color) {
        val session = this.session.value ?: return
        Log.i(CHESS_LOG_TAG, "Resigning ${session.gameId} on behalf of $color")
        viewModelScope.launch { uiModule.requestResign(session.gameId, color) }
    }

    /**
     * Whose tap Move Ready is.
     *
     * The whole screen is one target, so in a local game, two people at one board and one phone,
     * it belongs to whoever is to move. In an engine or remote game there is only one person here
     * to tap it. The protocol asks the UI for this because only the UI knows how the tap was made.
     */
    private fun requesterColor(session: GameSession): Color? = when (session.mode) {
        GameMode.LOCAL -> (orchestratorState.value as? OrchestratorState.AwaitingPlayerMove)?.color
        GameMode.ENGINE, GameMode.REMOTE -> session.playerColor
    }

    fun offerDraw() {
        val gameId = session.value?.gameId ?: return
        viewModelScope.launch { uiModule.requestDraw(gameId) }
    }

    fun abort() {
        val gameId = session.value?.gameId ?: return
        viewModelScope.launch { uiModule.requestAbort(gameId) }
    }

    // -- Board debug --

    /**
     * Reads the board once and keeps the packet alongside its decoding.
     *
     * Goes to the connection rather than through [BleBoardModule] on purpose: the module refuses a
     * reading that contains an unreadable square, and an unreadable square is one of the things
     * this screen exists to show. Nothing here touches the orchestrator, so a read is safe to make
     * at any time - though on a simulated board it still advances the script by one move.
     */
    fun readBoardForDebug() {
        if (_isReadingBoard.value) return
        viewModelScope.launch {
            _isReadingBoard.value = true
            val index = (_boardReading.value?.index ?: 0) + 1
            try {
                val bytes = connection.readBoardState()
                Log.i(CHESS_LOG_TAG, "Debug read #$index: ${bytes.size} bytes")
                _boardReading.value = when (val parsed = parseBoardState(bytes)) {
                    is PacketResult.Ok -> BoardReading(index, bytes, parsed.value, null)
                    // The bytes are kept even when they do not decode: they are the evidence.
                    is PacketResult.Invalid -> BoardReading(index, bytes, null, parsed.reason)
                }
            } catch (e: Exception) {
                _boardReading.value = BoardReading(index, ByteArray(0), null, e.message ?: e.toString())
            } finally {
                _isReadingBoard.value = false
            }
        }
    }

    // -- Settings --

    fun savePlayerName(name: String) {
        viewModelScope.launch { settingsRepository.setPlayerName(name) }
    }

    fun saveModuleSettings(moduleName: String, values: Map<String, String>) {
        viewModelScope.launch { settingsRepository.saveModuleSettings(moduleName, values) }
    }

    fun setAnnounceOpponentMoves(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAnnounceOpponentMoves(enabled) }
    }

    fun setBoardDebugUnlocked(unlocked: Boolean) {
        viewModelScope.launch { settingsRepository.setBoardDebugUnlocked(unlocked) }
    }

    override fun onCleared() {
        super.onCleared()
        // Release the board so another phone can connect; the firmware accepts one central only.
        connection.close()
        orchestrator.stop()
        moduleScope.cancel()
        speaker.shutdown()
    }

}
