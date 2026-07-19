# Smart Chess Android — implementation plan and status

## Phases

| Phase | Scope | Status |
|---|---|---|
| A | Refactor scp remote adapters off `java.net.http` onto OkHttp | **Done** |
| B | Scaffold app + Local PvP (BLE board, Compose UI, settings) | **Done** — tag `phase-b-local-pvp` |
| C | PvE via on-device Carballo engine | Not started |
| D | Remote PvP via Lichess | Not started |
| E | Games history (Room + PGN + move visor) | Placeholder screen only |

## Reviewing the history

Every commit builds on its own, and each is self-contained: the dependency,
manifest entry and resources a feature needs land in the same commit as its
code. Milestones are tagged, so a phase is a commit range.

- **Phase B — local PvP**: `root..phase-b-local-pvp` (9 commits)

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
- BLE cannot be exercised on an emulator. Anything touching the board needs the
  real hardware.

## Ordered next steps

1. **Phase C** — add `scp:engine-carballo` to `app/build.gradle.kts` and to the
   substitutions in `settings.gradle.kts`, extend `GameSetupScreen` with engine
   selection driven by `ParameterDescriptor` (`ParameterForm.kt` already renders
   these generically, so no bespoke UI), play a full engine game. Tag
   `phase-c-engine`.
2. **Phase D** — wire `scp:remote-lichess`, platform/seek forms, seeking UI, and
   make the clocks follow the remote authority rather than the local tick.
   Needs a Lichess token in Settings. Tag `phase-d-remote`.
3. **Phase E** — Room-backed history replacing the placeholder `HistoryScreen`:
   PGN, move-by-move visor, player identities. Then wire the remote-Stockfish
   engine adapter. Tag `phase-e-history`.

## Decisions already taken (carry forward)

- `fen2img` is **dropped**. The board view is native Compose
  (`ui/components/ChessBoardView.kt`) — rendering a bitmap per move buys nothing
  and a vector board scales to any density for free.
- The remote adapters use **OkHttp**; `java.net.http.HttpClient` is unavailable
  on Android below API 34.
- No empty milestone-marker commits — tag the last real commit of a phase.
- API keys and tokens live in `SettingsRepository` (DataStore, app-private) and
  are never written into game history.
