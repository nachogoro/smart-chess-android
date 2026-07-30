package com.nachogoro.smartchess.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

/**
 * A request the orchestrator is blocked on, waiting for the user to answer.
 *
 * The orchestrator drives setup by calling suspending methods on [UIModule]. Each call parks a
 * [CompletableDeferred] here and awaits it; the Compose layer observes the pending request,
 * renders a form, and completes (or cancels) it. Cancelling makes the corresponding
 * `configureXxx` call return [UIError.Cancelled], which unwinds the orchestrator's setup flow.
 */
data class PendingRequest<I, O>(
    val input: I,
    private val deferred: CompletableDeferred<O>,
) {
    fun complete(value: O) = deferred.complete(value)
    fun cancel() = deferred.cancel()
}

/**
 * The app's [UIModule]. This and the board module are the only platform-specific pieces of the
 * SCP stack; the orchestrator, rules engine and adapters are shared with the desktop build.
 *
 * State is exposed as Compose snapshot state rather than flows. The orchestrator invokes these
 * methods from its own event-loop coroutine, which is fine: snapshot state is safe to write from
 * any thread, and Compose schedules recomposition on the main thread.
 */
class AndroidUIModule : UIModule {

    // -- Pending requests, observed by the setup and game screens --

    var pendingGameModeSelection by mutableStateOf<PendingRequest<List<GameMode>, GameMode>?>(null)
        private set
    var pendingLocalGameConfig by mutableStateOf<PendingRequest<String?, LocalGameConfig>?>(null)
        private set
    var pendingEngineSelection by mutableStateOf<PendingRequest<List<EngineInfo>, String>?>(null)
        private set
    var pendingEngineGameConfig by mutableStateOf<PendingRequest<ConfigureEngineGameParams, EngineGameConfig>?>(null)
        private set
    var pendingRemotePlatformSelection by mutableStateOf<PendingRequest<List<PlatformInfo>, String>?>(null)
        private set
    var pendingRemoteGameConfig by mutableStateOf<PendingRequest<ConfigureRemoteGameParams, RemoteGameConfig>?>(null)
        private set
    var pendingPromotion by mutableStateOf<PendingRequest<PromotionContext, PromotionPiece>?>(null)
        private set
    var pendingDisambiguation by mutableStateOf<PendingRequest<DisambiguationContext, String>?>(null)
        private set
    var pendingDrawResponse by mutableStateOf<PendingRequest<String, Boolean>?>(null)
        private set

    // -- One-way notifications --

    var lastOpponentMove by mutableStateOf<ShowOpponentMoveParams?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var desyncInfo by mutableStateOf<DesyncInfo?>(null)
        private set
    var isSeeking by mutableStateOf(false)
        private set
    var lastGameStartedInfo by mutableStateOf<GameStartedInfo?>(null)
        private set
    var lastGameSummary by mutableStateOf<GameSummary?>(null)
        private set

    /** Latest position known to the app, used by the optional board viewer. */
    var currentFen by mutableStateOf<String?>(null)
        private set

    data class PromotionContext(val move: String, val color: Color)
    data class DisambiguationContext(val moves: List<String>, val color: Color)
    data class DesyncInfo(val expectedFen: String, val message: String)

    // -- Events emitted by the UI --

    /**
     * Games that have finished, for consumers that must not miss one.
     *
     * [lastGameSummary] is Compose state and drives the result dialog, but observing it outside a
     * composition would depend on the recomposer pumping snapshot notifications. Persistence must
     * not rest on that, so finished games are also published here. Replay covers a collector that
     * subscribes fractionally after a game ends.
     */
    private val _gameEnded = MutableSharedFlow<GameSummary>(replay = 1)
    val gameEnded: SharedFlow<GameSummary> = _gameEnded

    /**
     * Opponent moves, for consumers that act on the move rather than display it.
     *
     * [lastOpponentMove] is the state the screen renders and stands until it is cleared, which is
     * the wrong shape for spoken announcements: those must happen once, when the move arrives, and
     * not again because something recomposed. No replay, for the same reason - a collector that
     * subscribes later has missed a move the player has long since played.
     */
    private val _opponentMoved = MutableSharedFlow<ShowOpponentMoveParams>(extraBufferCapacity = 4)
    val opponentMoved: SharedFlow<ShowOpponentMoveParams> = _opponentMoved

    private val _newGameRequested = MutableSharedFlow<Unit>(replay = 1)
    private val _moveReady = MutableSharedFlow<MoveReadyEvent>(extraBufferCapacity = 4)
    private val _resignRequested = MutableSharedFlow<ResignRequestedEvent>(extraBufferCapacity = 4)
    private val _drawRequested = MutableSharedFlow<DrawRequestedEvent>(extraBufferCapacity = 4)
    private val _abortRequested = MutableSharedFlow<AbortRequestedEvent>(extraBufferCapacity = 4)

    override val newGameRequested: SharedFlow<Unit> = _newGameRequested
    override val moveReady: SharedFlow<MoveReadyEvent> = _moveReady
    override val resignRequested: SharedFlow<ResignRequestedEvent> = _resignRequested
    override val drawRequested: SharedFlow<DrawRequestedEvent> = _drawRequested
    override val abortRequested: SharedFlow<AbortRequestedEvent> = _abortRequested

    // -- UIModule: setup --

    override suspend fun selectGameMode(availableModes: List<GameMode>): Result<UIError, GameMode> =
        awaitAnswer({ pendingGameModeSelection = it }, { pendingGameModeSelection = null }, availableModes)

    override suspend fun configureLocalGame(errorMessage: String?): Result<UIError, LocalGameConfig> =
        awaitAnswer({ pendingLocalGameConfig = it }, { pendingLocalGameConfig = null }, errorMessage)

    override suspend fun selectEngine(engines: List<EngineInfo>): Result<UIError, String> =
        awaitAnswer({ pendingEngineSelection = it }, { pendingEngineSelection = null }, engines)

    override suspend fun configureEngineGame(params: ConfigureEngineGameParams): Result<UIError, EngineGameConfig> =
        awaitAnswer({ pendingEngineGameConfig = it }, { pendingEngineGameConfig = null }, params)

    override suspend fun selectRemotePlatform(platforms: List<PlatformInfo>): Result<UIError, String> =
        awaitAnswer({ pendingRemotePlatformSelection = it }, { pendingRemotePlatformSelection = null }, platforms)

    override suspend fun configureRemoteGame(params: ConfigureRemoteGameParams): Result<UIError, RemoteGameConfig> =
        awaitAnswer({ pendingRemoteGameConfig = it }, { pendingRemoteGameConfig = null }, params)

    // -- UIModule: in-game prompts --

    override suspend fun promptPromotion(move: String, color: Color): Result<UIError, PromotionPiece> =
        awaitAnswer({ pendingPromotion = it }, { pendingPromotion = null }, PromotionContext(move, color))

    override suspend fun promptMoveDisambiguation(moves: List<String>, color: Color): Result<UIError, String> =
        awaitAnswer({ pendingDisambiguation = it }, { pendingDisambiguation = null }, DisambiguationContext(moves, color))

    override suspend fun promptDrawResponse(gameId: String, from: String): Result<UIError, Boolean> =
        awaitAnswer({ pendingDrawResponse = it }, { pendingDrawResponse = null }, from)

    // -- UIModule: notifications --

    override suspend fun onSeekingGame() {
        isSeeking = true
    }

    override suspend fun onGameStarted(info: GameStartedInfo) {
        isSeeking = false
        currentFen = info.initialFen
        lastGameSummary = null
        lastGameStartedInfo = info
    }

    override suspend fun onGameEnded(summary: GameSummary) {
        currentFen = summary.finalFen
        lastGameSummary = summary
        _gameEnded.emit(summary)
    }

    override suspend fun showOpponentMove(params: ShowOpponentMoveParams): Result<UIError, Unit> {
        currentFen = params.fen
        lastOpponentMove = params
        _opponentMoved.emit(params)
        return Result.Success(Unit)
    }

    override suspend fun displayError(message: String): Result<UIError, Unit> {
        lastError = message
        return Result.Success(Unit)
    }

    override suspend fun boardDesync(expectedFen: String, message: String): Result<UIError, Unit> {
        desyncInfo = DesyncInfo(expectedFen, message)
        return Result.Success(Unit)
    }

    // -- Called by the Compose layer --

    suspend fun requestNewGame() = _newGameRequested.emit(Unit)
    suspend fun signalMoveReady(gameId: String) = _moveReady.emit(MoveReadyEvent(gameId))
    suspend fun requestResign(gameId: String) = _resignRequested.emit(ResignRequestedEvent(gameId))
    suspend fun requestDraw(gameId: String) = _drawRequested.emit(DrawRequestedEvent(gameId))
    suspend fun requestAbort(gameId: String) = _abortRequested.emit(AbortRequestedEvent(gameId))

    /** Records the position after a move the app itself made, keeping the viewer in step. */
    fun updateFen(fen: String) {
        currentFen = fen
    }

    fun clearError() { lastError = null }
    fun clearDesync() { desyncInfo = null }
    fun clearOpponentMove() { lastOpponentMove = null }
    fun clearGameStartedInfo() { lastGameStartedInfo = null }
    fun clearGameSummary() { lastGameSummary = null }

    /**
     * Cancels every pending setup request, unwinding the orchestrator's setup flow.
     *
     * Called when the user backs out of the setup screen. Without this the orchestrator would stay
     * parked on a `configureXxx` call forever and refuse to start a subsequent game.
     */
    fun cancelAllPendingRequests() {
        isSeeking = false
        pendingGameModeSelection?.cancel()
        pendingLocalGameConfig?.cancel()
        pendingEngineSelection?.cancel()
        pendingEngineGameConfig?.cancel()
        pendingRemotePlatformSelection?.cancel()
        pendingRemoteGameConfig?.cancel()
    }

    /**
     * Parks a request in Compose state and suspends until the UI answers it.
     *
     * A cancelled deferred surfaces as [UIError.Cancelled], which every caller in the orchestrator
     * treats as "the user backed out".
     */
    private suspend fun <I, O> awaitAnswer(
        publish: (PendingRequest<I, O>) -> Unit,
        clear: () -> Unit,
        input: I,
    ): Result<UIError, O> {
        val deferred = CompletableDeferred<O>()
        publish(PendingRequest(input, deferred))
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            clear()
        }
    }
}
