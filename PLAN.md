# Smart Chess Android — implementation plan and status

## Phases

| Phase | Scope | Status |
|---|---|---|
| A | Refactor scp remote adapters off `java.net.http` onto OkHttp | **Done** |
| B | Scaffold app + Local PvP (BLE board, Compose UI, settings) | **Done** — tag `phase-b-local-pvp` |
| C | PvE via on-device Carballo engine | **Done** — tag `phase-c-engine` |
| D | Remote PvP via Lichess | **Done** — tag `phase-d-remote` (untested against live Lichess) |
| E | Games history (Room + PGN + move visor) | **Done** — tag `phase-e-history` |

## Reviewing the history

Every commit builds on its own, and each is self-contained: the dependency,
manifest entry and resources a feature needs land in the same commit as its
code. Milestones are tagged, so a phase is a commit range.

- **Phase B — local PvP**: `phase-b-local-pvp` (9 commits)
- **Phase C — engine play**: `phase-b-local-pvp..phase-c-engine` (3, one a plan update)
- **Phase D — remote play**: `phase-c-engine..phase-d-remote` (3, one a plan update)
- **Phase E — games history**: `phase-d-remote..phase-e-history` (1 commit)

Verify any commit with `./gradlew :app:assembleDebug`; the native library check
is `./gradlew :app:connectedDebugAndroidTest` and needs a running device.

## Cross-repo state

Phases A and B needed changes in two sibling repos, both committed:

- `simple-chess-protocol` — remote adapters moved to OkHttp; modules emit Java
  17 bytecode (D8 rejects class-file 65).
- `simple-chess-kotlin` — `java.lang.ref.Cleaner` replaced with a
  `PhantomReference`-based `NativeCleaner`. `Cleaner` is API 33+, so the
  published 1.0.0 is unusable on most Android devices. **Core library
  desugaring does not fix this** — `desugar_jdk_libs` covers no `java.lang.ref`
  classes at all.

Both are consumed as Gradle **composite builds**, so edits there are picked up
without publishing. Once the `NativeCleaner` fix reaches Maven Central, the
`simple-chess-kotlin` composite build can be dropped for the published artifact.

## Testing notes

- `NativeChessLibraryTest` must run on a device **below API 33**, or it cannot
  catch a `Cleaner` regression. The `Pixel_XL_API_30` AVD is the right target;
  boot it with `emulator -avd Pixel_XL_API_30 -no-window -no-audio`.
- Remote play has **never run against live Lichess** — it needs a real API
  token with the `board:play` scope. `LichessHttpApiTest` covers the HTTP layer
  under ART against a mock server, and the adapter's own state machine is
  covered by JVM tests in the protocol repo, but the two have not been put
  together against the real service.
- BLE cannot be exercised on an emulator. Anything touching the board needs the
  real hardware. `EngineGameFlowTest` works around this with a fake board so the
  rest of the stack can still be tested end to end on device; the same trick is
  available for phases D and E.
- The orchestrator asks engines for a move with no time or depth limit, so every
  engine move is a fixed depth-10 search (~1.5s for Carballo on the API 30
  emulator). If a slower engine or device makes that unpleasant, the call site
  to change is `MoveHandling.kt` in the protocol repo - the UI has no hook.

## Ordered next steps

All five planned phases are done. What remains is verification against real
hardware and services, plus optional extras:

1. **Play a real game on the physical board.** Nothing in the app has ever
   talked to the actual BLE board — no emulator provides Bluetooth. This is the
   single largest untested surface and it gates every mode.
2. **Play a real game on Lichess**, with an API token carrying the `board:play`
   scope.
3. ~~Wire `scp:engine-remote-stockfish` as a second engine.~~ **Done** — tag
   `second-engine`. It needed no UI work, confirming the generic-form claim.

## Decisions already taken (carry forward)

- `fen2img` is **dropped**. The board view is native Compose
  (`ui/components/ChessBoardView.kt`) — rendering a bitmap per move buys nothing
  and a vector board scales to any density for free.
- The remote adapters use **OkHttp**; `java.net.http.HttpClient` is unavailable
  on Android below API 34.
- No empty milestone-marker commits — tag the last real commit of a phase.
- API keys and tokens live in `SettingsRepository` (DataStore, app-private) and
  are never written into game history.
