package com.nachogoro.smartchess

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nachogoro.smartchess.core.ChessViewModel
import com.nachogoro.smartchess.core.Screen
import com.nachogoro.smartchess.data.AppLanguage
import com.nachogoro.smartchess.ble.BoardConnectionState
import com.nachogoro.smartchess.ui.screens.BoardDebugScreen
import com.nachogoro.smartchess.ui.screens.ConnectionScreen
import com.nachogoro.smartchess.ui.screens.GameScreen
import com.nachogoro.smartchess.ui.screens.GameSetupScreen
import com.nachogoro.smartchess.ui.screens.HistoryScreen
import com.nachogoro.smartchess.ui.screens.MainMenuScreen
import com.nachogoro.smartchess.ui.screens.SettingsScreen
import com.nachogoro.smartchess.ui.theme.SmartChessTheme
import scp.protocol.GameMode

/** AppCompatActivity for its per-app language support; the UI itself is entirely Compose. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartChessTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmartChessApp()
                }
            }
        }
    }
}

@Composable
private fun SmartChessApp(viewModel: ChessViewModel = viewModel()) {
    val screen by viewModel.screen.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val session by viewModel.session.collectAsState()
    val orchestratorState by viewModel.orchestratorState.collectAsState()
    val setupMode by viewModel.setupMode.collectAsState()
    val playedGames by viewModel.playedGames.collectAsState()
    val boardReading by viewModel.boardReading.collectAsState()
    val isReadingBoard by viewModel.isReadingBoard.collectAsState()

    when (screen) {
        Screen.CONNECTION -> ConnectionScreen(
            state = connectionState,
            isConnecting = isConnecting,
            onConnect = viewModel::connectToBoard,
            onContinueWithoutBoard = viewModel::continueWithoutBoard,
            onConnected = viewModel::backToMenu,
        )

        Screen.MAIN_MENU -> MainMenuScreen(
            availableModes = viewModel.availableModes,
            connectionState = connectionState,
            onSelectMode = viewModel::startGame,
            onHistory = viewModel::openHistory,
            onSettings = viewModel::openSettings,
            showBoardDebug = settings.boardDebugUnlocked,
            onBoardDebug = viewModel::openBoardDebug,
            onReconnect = viewModel::connectToBoard,
        )

        Screen.SETTINGS -> {
            BackHandler { viewModel.backToMenu() }
            SettingsScreen(
                settings = settings,
                connectionState = connectionState,
                // Read from AppCompat rather than held in state: changing it recreates the
                // activity, so this is re-read with the new value already in force.
                language = AppLanguage.current(),
                onSetLanguage = AppLanguage::apply,
                onSavePlayerName = viewModel::savePlayerName,
                onSetAnnounceOpponentMoves = viewModel::setAnnounceOpponentMoves,
                onClearModuleSettings = { viewModel.saveModuleSettings(it, emptyMap()) },
                onBack = viewModel::backToMenu,
            )
        }

        Screen.GAME_SETUP -> {
            // Backing out must cancel the orchestrator's pending setup requests, not just navigate.
            BackHandler { viewModel.cancelSetup() }
            GameSetupScreen(
                mode = setupMode ?: GameMode.LOCAL,
                uiModule = viewModel.uiModule,
                settings = settings,
                onBack = viewModel::cancelSetup,
                onGameStarted = viewModel::onGameStarted,
                onSaveModuleSettings = viewModel::saveModuleSettings,
                onCancelSeek = viewModel::cancelSeek,
            )
        }

        Screen.GAME -> {
            // A game is in progress; the system back gesture must not silently abandon it.
            BackHandler { /* intentionally inert - use Resign or Abort */ }
            GameScreen(
                session = session,
                orchestratorState = orchestratorState,
                uiModule = viewModel.uiModule,
                onMoveReady = viewModel::signalMoveReady,
                onResign = viewModel::resign,
                onOfferDraw = viewModel::offerDraw,
                onAbort = viewModel::abort,
                onGameEnd = viewModel::onGameFinished,
            )
        }

        Screen.BOARD_DEBUG -> {
            BackHandler { viewModel.backToMenu() }
            BoardDebugScreen(
                reading = boardReading,
                isReading = isReadingBoard,
                // Identified rather than Ready, as on the menu: the link is down between reads by
                // design and the read itself brings it back up.
                boardReady = connectionState is BoardConnectionState.Identified,
                onRead = viewModel::readBoardForDebug,
                onBack = viewModel::backToMenu,
            )
        }

        Screen.HISTORY -> {
            BackHandler { viewModel.backToMenu() }
            HistoryScreen(
                games = playedGames,
                onDelete = viewModel::deleteGame,
                onBack = viewModel::backToMenu,
            )
        }
    }

    // Try to reach the board as soon as the app opens, so the common case needs no interaction.
    LaunchedEffect(Unit) {
        viewModel.connectToBoard()
    }
}
