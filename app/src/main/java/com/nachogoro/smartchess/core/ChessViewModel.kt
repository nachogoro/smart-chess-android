package com.nachogoro.smartchess.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nachogoro.smartchess.ble.BleBoardConnection
import com.nachogoro.smartchess.ble.BleBoardModule
import com.nachogoro.smartchess.ble.BoardConnectionState
import com.nachogoro.smartchess.data.AppSettings
import com.nachogoro.smartchess.data.SettingsRepository
import com.nachogoro.smartchess.ui.AndroidUIModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import scp.game.GameController
import scp.orchestrator.GameSession
import scp.orchestrator.Orchestrator
import scp.orchestrator.OrchestratorState
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
}

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
    private val engines: List<EngineAdapterModule> = emptyList()
    private val remotes: List<RemoteAdapterModule> = emptyList()

    private val orchestrator = Orchestrator(
        board = board,
        gameController = gameController,
        ui = uiModule,
        engines = engines,
        remotes = remotes,
    )

    private val settingsRepository = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val connectionState: StateFlow<BoardConnectionState> = connection.state

    private val _screen = MutableStateFlow(Screen.CONNECTION)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _setupMode = MutableStateFlow<GameMode?>(null)
    val setupMode: StateFlow<GameMode?> = _setupMode.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // The orchestrator exposes `state` and `session` as plain mutable properties rather than
    // flows, so they are polled and mirrored here for Compose to observe.
    private val _orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val orchestratorState: StateFlow<OrchestratorState> = _orchestratorState.asStateFlow()

    private val _session = MutableStateFlow<GameSession?>(null)
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    val availableModes: List<GameMode> = buildList {
        add(GameMode.LOCAL)
        if (engines.isNotEmpty()) add(GameMode.ENGINE)
        if (remotes.isNotEmpty()) add(GameMode.REMOTE)
    }

    init {
        viewModelScope.launch(Dispatchers.Default) { orchestrator.start() }

        viewModelScope.launch {
            while (isActive) {
                _orchestratorState.value = orchestrator.state
                _session.value = orchestrator.session
                delay(POLL_INTERVAL_MS)
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
    fun backToMenu() { _screen.value = Screen.MAIN_MENU }

    fun startGame(mode: GameMode) {
        _setupMode.value = mode
        _screen.value = Screen.GAME_SETUP
        viewModelScope.launch { uiModule.requestNewGame() }
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
     *
     * This is the equivalent of pressing the clock: the orchestrator reads the board and works out
     * what was played.
     */
    fun signalMoveReady() {
        val gameId = _session.value?.gameId ?: return
        viewModelScope.launch { uiModule.signalMoveReady(gameId) }
    }

    fun resign() {
        val gameId = _session.value?.gameId ?: return
        viewModelScope.launch { uiModule.requestResign(gameId) }
    }

    fun offerDraw() {
        val gameId = _session.value?.gameId ?: return
        viewModelScope.launch { uiModule.requestDraw(gameId) }
    }

    fun abort() {
        val gameId = _session.value?.gameId ?: return
        viewModelScope.launch { uiModule.requestAbort(gameId) }
    }

    // -- Settings --

    fun savePlayerName(name: String) {
        viewModelScope.launch { settingsRepository.setPlayerName(name) }
    }

    fun saveModuleSettings(moduleName: String, values: Map<String, String>) {
        viewModelScope.launch { settingsRepository.saveModuleSettings(moduleName, values) }
    }

    override fun onCleared() {
        super.onCleared()
        // Release the board so another phone can connect; the firmware accepts one central only.
        connection.close()
        orchestrator.stop()
        moduleScope.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
    }
}
