# Smart Chess Android — architecture, status and how to pick this up

A single document to read before touching this code. It covers what the app is, how it is put
together, what has been built and verified, what has *not*, how to review the work, and what to do
next.

Companion documents: `README.md` (how to build and the native-library handling), `PLAN.md` (live
phase status). This file is the one that explains *why* things are the way they are.

---

## 1. What this app is

The physical board is a **dumb sensor**. It reports which colour occupies each of its 64 squares
over Bluetooth, and nothing else. It cannot tell a knight from a bishop, it has no buttons, and it
has no idea what chess is.

Everything else lives in this app: the rules, working out what move was played, the clocks, the
engine, and online play. The design goal is that a player interacts with the board and almost never
with the phone.

### How a move happens

1. The player moves the pieces on the physical board.
2. The player **taps the phone screen** — the equivalent of pressing a chess clock.
3. The app reads all 64 squares over BLE.
4. The orchestrator diffs that reading against the current game position and infers the move.
5. If the reading is ambiguous (two legal moves look identical to a colour-only sensor) or a pawn
   promoted, the app asks. Otherwise nothing is asked.

This is the single most important thing to understand about the architecture, because it explains
an otherwise odd omission: **there is no interactive on-screen board.** The board is the input
device. An on-screen board would be a second, competing source of truth. There is a read-only board
view behind an optional toggle, purely so a player can confirm the physical position and the app's
idea of it still agree.

It also explains a protocol constraint worth knowing early: there is no "the user played e4" event
anywhere. The UI only ever says *"I have finished moving"*, and the orchestrator works out the rest.

---

## 2. Architecture

The app is one client of the **Simple Chess Protocol** (SCP). The specification lives in the sibling
`smart-chess-protocol` repo; its Kotlin module implementations live in `smart-chess-scp`, which
this app consumes. SCP is a star topology: the Orchestrator is the hub, and modules never
talk to each other directly.

Of SCP's six modules, this app supplies **two**. The rest are reused unchanged, and are shared with
the desktop app.

| SCP module | Where it comes from |
|---|---|
| **Board** | **This app** — `ble/BleBoardModule`, reads the physical board over BLE |
| **UI** | **This app** — `ui/AndroidUIModule`, Compose |
| Orchestrator | `smart-chess-scp`, unchanged |
| Game Controller | `smart-chess-scp`, unchanged (wraps the native rules engine) |
| Engine Adapter | `scp:engine-carballo` and `scp:engine-remote-stockfish`, unchanged |
| Remote Adapter | `scp:remote-lichess`, unchanged |

```
Physical board ──BLE──> BleBoardModule ──┐
                                          ├──> Orchestrator ──> Game Controller ──> native rules engine
              AndroidUIModule (Compose) ──┘         │
                                                    ├──> CarballoAdapter    (on-device engine)
                                                    ├──> RemoteStockfishAdapter (chess-api.com)
                                                    └──> LichessAdapter     (online play)
```

### The inversion that shapes the UI code

SCP does not work the way an app normally does. **The orchestrator asks the questions.** It calls
suspending methods on the UI module — "which mode?", "configure this engine", "which promotion
piece?" — and waits for answers.

`AndroidUIModule` bridges that to Compose: each call parks a `CompletableDeferred` in Compose
snapshot state and suspends. `GameSetupScreen` renders whichever question is outstanding and
completes the deferred. Cancelling a deferred surfaces as `UIError.Cancelled`, which unwinds the
orchestrator's setup flow.

Two consequences that are easy to get wrong:

- **Backing out must cancel pending requests**, not just navigate. Otherwise the orchestrator stays
  parked on a `configureXxx` call forever and refuses to start another game.
- **The mode is chosen in the main menu**, before the orchestrator asks. The setup screen answers
  that prompt automatically from the menu choice.

### Forms are generated, not written

Engine and remote adapters declare their options as `ParameterDescriptor`s — type, description,
default, min/max, `sensitive`, and `visible_when` conditional visibility. `ui/components/ParameterForm.kt`
renders any such list generically.

This is why adding an engine or platform needs no UI work. Carballo's "target Elo" field appearing
only once strength limiting is enabled is not coded anywhere in this app; it falls out of the
descriptor. The claim has been tested rather than assumed: adding a second engine (remote Stockfish)
touched the two Gradle files and one line of `ChessViewModel`, and no UI file at all. Adapters reject options they did not declare, so **hidden fields must not be submitted**
— `toTypedValues()` filters to visible descriptors, and that filtering is load-bearing, not cosmetic.

### Where the code lives

```
ble/            BleBoardConnection   scan, connect, GATT, reconnection      (350)
                BoardPackets         wire format decoding, service UUIDs    (195)
                BleBoardModule       adapts the above to SCP's BoardModule  (100)
                BlePermissions       the version-dependent permission set    (48)
core/           ChessViewModel       owns the SCP stack for the app's life  (269)
data/           SettingsRepository   nickname + per-module settings          (84)
                GameHistory          Room entity, DAO, database             (118)
                GameHistoryRepository                                        (41)
                Pgn                  PGN export                              (84)
ui/             AndroidUIModule      the SCP UI module                      (228)
ui/components/  ParameterForm        generic descriptor-driven forms        (221)
                ChessBoardView       read-only board from a FEN             (150)
ui/screens/     ConnectionScreen     BLE permissions and connection         (193)
                MainMenuScreen       modes, derived from compiled adapters  (120)
                GameSetupScreen      every setup form                       (569)
                GameScreen           clocks, tap-to-move, board toggle      (432)
                HistoryScreen        past games, PGN, replay                (333)
                SettingsScreen       nickname, board info, saved settings   (157)
MainActivity                         screen graph                          (122)
```

About 3,900 lines of app code and 900 of instrumented tests.

### Clocks

Worth its own note, because it differs by mode and the difference is invisible in the UI code.

`GameSession.clocks` only ever holds **a snapshot taken at the last move**. Both clock panels count
down locally from that snapshot; the countdown is cosmetic and each update corrects it.

- **Local and engine games** are created with `Authority.LOCAL`. The Game Controller computes clocks
  from elapsed time and runs a flag-fall timer, so timeouts are real and end the game.
- **Remote games** are created with `Authority.EXTERNAL`. No flag-fall timer runs locally, and clock
  values only change when Lichess sends new ones — which happens on the opponent's move, not yours.
  The display therefore drifts slightly in your favour after your own move and snaps back on the
  next server update. **A remote clock reaching zero on screen means nothing**; the server decides,
  and a timeout arrives as an ordinary game-ended notification.

---

## 3. What has been built

Five phases plus a follow-up, each a tagged commit range. All 18 commits build standalone; all 22
instrumented tests pass on the API 30 emulator.

| Phase | Scope | Tag |
|---|---|---|
| A | scp remote adapters moved off `java.net.http` onto OkHttp | *(in smart-chess-scp)* |
| B | Scaffold, BLE board, Compose UI, settings, local PvP | `phase-b-local-pvp` |
| C | Play an on-device engine (Carballo) | `phase-c-engine` |
| D | Play a remote opponent (Lichess) | `phase-d-remote` |
| E | Games history: Room, PGN, move-by-move replay | `phase-e-history` |
| — | Remote Stockfish added as a second engine | `second-engine` |

The follow-up after phase E was deliberately a test of the architecture rather than a feature.
Adding a second engine was supposed to need no UI work, since the setup forms are generated from
whatever parameters an adapter declares — and it did not: two Gradle files and one line of
`ChessViewModel`. It also made the **engine picker reachable for the first time**. With a single
engine the orchestrator selects it silently, so that branch had never executed in any build; it now
has a test, using a stub engine that always plays the same move, because the orchestrator resolves
the user's answer back to an adapter *by name* and only picking something other than the first entry
proves that mapping works.

### Changes in sibling repos

Two sibling repos needed changes; both are committed, and both are consumed as Gradle **composite
builds**, so edits there are picked up with no publishing step.

- **`smart-chess-scp`** @ `b24100e` — the Lichess and remote-Stockfish adapters use OkHttp
  now, because `java.net.http.HttpClient` does not exist on Android below API 34. The modules also
  emit Java 17 bytecode while compiling on the JDK 21 toolchain, because D8 rejects class-file 65.
- **`simple-chess-kotlin`** (published as `simplechess` 1.1.0) — `java.lang.ref.Cleaner` replaced
  with a `PhantomReference`-based `NativeCleaner`. `Cleaner` is API 33+, so the earlier `simplechess`
  1.0.0 threw `NoClassDefFoundError` on most Android devices at the first move. **Core library
  desugaring does not fix this** — `desugar_jdk_libs` covers no `java.lang.ref` classes at all.
  Once this reaches Maven Central, the composite build for this repo can be dropped.

### Native libraries: the AAR does the heavy lifting

The app consumes `io.github.nachogoro:simplechess-android` (the AAR) from Maven Central, which
bundles the C library's `.so` and the JNA AAR. So the app itself carries **no** native setup — the
three delivery problems that used to live here (committing `.so` in `jniLibs`, using the JNA AAR not
the JAR, stripping the jar's `native/**` resources) are all the AAR's responsibility now.

The one thing the app's build does: the scp modules pull the plain `simplechess` **jar**
transitively, which duplicates the AAR's classes, so the jar is excluded config-wide in
`app/build.gradle.kts`. That is safe here (unlike for JNA) because the AAR is a *different* module,
`simplechess-android` — a config-wide exclude of `simplechess` leaves it untouched — and it also
drops the desktop JNA the jar depended on.

The remaining hard requirement is the version: **`simplechess` 1.1.0 or newer**. 1.0.0 freed native
pointers through `java.lang.ref.Cleaner` (API 33+), throwing `NoClassDefFoundError` on older Android;
1.1.0 replaced it with a `PhantomReference`-based cleaner. Core library desugaring does not fix the
1.0.0 problem — `desugar_jdk_libs` covers no `java.lang.ref` classes.

`NativeChessLibraryTest` is the guard, and it **must run on a device below API 33** or it cannot
catch a `Cleaner` regression at all.

### Tests

All instrumented — they must run on a device or emulator, because every one of them is about
runtime behaviour that compiles perfectly well when broken.

| Test | What it protects |
|---|---|
| `NativeChessLibraryTest` | The native library actually loads and runs; plays real moves through the C rules engine |
| `CarballoEngineTest` | The engine runs under ART, loads its opening book from a classpath resource, declares its parameters, and rejects undeclared ones |
| `EngineGameFlowTest` | A full engine game through the **real orchestrator** — mode selection, config handshake, move inference, engine reply. Also that with two engines the app asks which to play and routes to the chosen one. Only the BLE board is faked |
| `LichessHttpApiTest` | The OkHttp rewrite under ART against a mock server: bearer token, error status codes, NDJSON line splitting, and that an abandoned stream is cut short rather than drained |
| `RemoteStockfishEngineTest` | The second engine under ART against a mock server: declared parameters, range validation, settings reaching the wire, and a failed request surfacing as a failure rather than a stall |
| `GameHistoryTest` | Room schema on a real database, PGN export shape, 80-column wrapping, and a game being recorded as soon as it is reported finished |

---

## 4. What has NOT been verified

Read this section before trusting anything above.

### The physical board has never been touched

**No part of this app has ever communicated with the real BLE board.** No emulator provides
Bluetooth. `ble/` compiles, and its service UUIDs match the firmware guide
(`../GUIA_APP_MOVIL_BLE_CHESSBOARD_V1-0-0_ES.md`), but the scan, connect, GATT read and packet
decode paths have never executed against hardware.

This gates **every** mode — local, engine and remote all get their moves from the board. It is the
largest remaining risk by a wide margin, and it is why the tests use a fake board: that let
everything *above* the board module be tested, and deliberately proves nothing about the board
module itself.

### Live Lichess has never been contacted

Remote play needs an API token with the `board:play` scope. The HTTP layer is tested under ART
against a mock server, and the adapter's state machine is tested on the JVM in smart-chess-scp,
but the two have never been put together against the real service.

### chess-api.com has never been contacted

The remote Stockfish engine is tested under ART against a mock server — its API URL is a constructor
parameter — but no request has ever gone to the real service. Carballo needs no network and is
verified, so engine play as a whole does work; it is the networked engine specifically that is
untried.

### Everything else is verified

Local play logic, engine play, history, PGN and the native library all run green on the API 30
emulator, and the app launches and reaches its first screen on-device without crashing.

---

## 5. How to review the work

The history was built to be read one commit at a time. Every commit builds on its own and is
self-contained: the Gradle dependency, manifest entry and resources a feature needs land in the
same commit as the code that needs them. Commit messages carry the *why*; that is where the
reasoning lives, so reading `git log` is not optional colour.

### Reviewing by feature

```sh
git log --oneline --decorate --reverse          # the whole story, 18 commits
git show <sha>                                  # one self-contained change

git log --oneline phase-b-local-pvp                    # local play  (9 commits)
git log --oneline phase-b-local-pvp..phase-c-engine    # engine      (3, one of them a plan update)
git log --oneline phase-c-engine..phase-d-remote       # remote      (3, one of them a plan update)
git log --oneline phase-d-remote..phase-e-history      # history     (1)
git log --oneline phase-e-history..second-engine       # 2nd engine  (2, one of them this document)

git tag -n99                                    # what each milestone claims, and what it does not
```

The tag annotations are deliberately explicit about what was and was not verified. If a tag and
this document ever disagree, trust the tag — it was written with the evidence in hand.

### Suggested reading order

If you read nothing else, read these five, in this order:

1. `9e9390d` **Run the native chess rules engine on Android** — the original native-loading traps
   and the reasoning (since superseded by the `simplechess-android` AAR; the reasoning still explains
   *why* Android native loading is hard).
2. `7dcf9ba` **Wire up local player vs local player** — how the parts join up.
3. `43a4b81` **Build the chess wrapper from source** — the `Cleaner` problem and why desugaring is a
   dead end.
4. `7181a29` **Keep a history of played games** — includes why persistence does *not* observe
   Compose state.
5. `27176d3` **Add remote Stockfish as a second engine** — the shortest illustration of what the
   generic parameter forms buy, and of a branch that had never run until a second engine existed.

Then, for the code itself: `ui/AndroidUIModule.kt` (the orchestrator-asks-the-questions bridge),
then `core/ChessViewModel.kt` (what owns what), then whichever screen interests you.

### Checking the claims rather than believing them

Every commit builds — verify it yourself rather than taking my word:

```sh
# Boot the emulator (API 30 matters: it is below 33, so it can catch a Cleaner regression)
$ANDROID_HOME/emulator/emulator -avd Pixel_XL_API_30 -no-window -no-audio &

./gradlew :app:assembleDebug                    # HEAD builds
./gradlew :app:connectedDebugAndroidTest        # 22 instrumented tests

# Every commit builds from a clean checkout — the loop I used, worth re-running after any rebase.
# The worktree must be a sibling of the other repos: `git worktree add` does not populate
# submodules, so the composite build has to resolve smart-chess-scp from the sibling checkout.
git worktree add -q --detach ../.verify-wt HEAD
for sha in $(git rev-list --reverse HEAD); do
  (cd ../.verify-wt && git checkout -q --detach $sha && cp ../smart-chess-android-app/local.properties . \
    && ./gradlew :app:assembleDebug -q >/dev/null 2>&1 \
    && echo "PASS $(git log -1 --format=%s $sha)" || echo "FAIL $(git log -1 --format=%s $sha)")
done
git worktree remove --force ../.verify-wt
```

Test results land in `app/build/reports/androidTests/connected/debug/index.html`.

---

## 6. How to keep going

### First, and before anything else: play a real game on the board

Everything else is guesswork until this happens. Suggested order, because each step isolates a
different failure:

1. **Connect.** Launch the app next to a powered board. The connection screen checks each BLE
   requirement separately — on Android 12+ it needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; on 11
   and below `ACCESS_FINE_LOCATION` *and* the system location toggle on. A missing permission makes
   scanning fail **silently**, which is why they are checked one at a time.
2. **Check what it reports.** Settings shows the connected board's capabilities — protocol version,
   dimensions, detection level, simulation mode. If that is wrong, `BoardPackets.kt` is where the
   wire format is decoded, and the firmware guide is `../GUIA_APP_MOVIL_BLE_CHESSBOARD_V1-0-0_ES.md`.
3. **Play a local game.** Move a piece, tap, see whether the right move is inferred. Turn the
   optional board view on while doing this — it exists precisely for this.
4. **Provoke the hard cases**: a capture, a castle, an en-passant, a promotion (should prompt), and
   a knight-vs-bishop ambiguity if the board only senses colour (should prompt to disambiguate).
5. **Then an engine game**, which adds nothing new on the board side but exercises the engine's
   moves being played back onto the physical board.

Expect the first real session to find something. When it does, the fake-board pattern in
`EngineGameFlowTest` is the tool: reproduce the bad reading as a fixed `BoardState` in a test, then
fix against it.

### Then Lichess

Create a token at <https://lichess.org/account/oauth/token> with the `board:play` scope, enter it
once in game setup (it is masked and persisted), and seek a casual game. Things to watch:

- The seek is withdrawn properly when you back out — check on the Lichess site that no seek is left
  hanging. This was a real bug, fixed in `d64ec89`; it deserves confirming against the live service.
- Clocks track the server. Expect the small optimistic drift described in §2 — that is correct
  behaviour, not a bug.
- A dropped connection surfaces as an error banner and the game stops updating. The adapter has
  **no reconnect logic**, so the game is dead at that point; Abort is the way out. If this proves
  annoying in practice, reconnection belongs in the adapter, in smart-chess-scp.

### Optional next features

- **Share/export PGN** via an Android share intent. `toPgn()` already produces importable output;
  only the intent is missing.
- **Engine search time.** The orchestrator asks for a move with no time or depth limit, so each
  engine falls back to its own setting. For Carballo that is a hardcoded depth-10 search (~1.5s on
  the emulator) that the user cannot change; remote Stockfish exposes `Depth` as a parameter, so
  there it is adjustable in the setup form. If Carballo's fixed depth proves unpleasant on a slow
  device, the call site to change is `MoveHandling.kt` in smart-chess-scp — the UI has no hook.
- ~~Drop the `simple-chess-kotlin` composite build once `NativeCleaner` ships to Maven Central.~~
  **Done** — `simplechess` 1.1.0 is on Maven Central; the app now pulls it transitively via scp
  rather than composite-building the library.

### Conventions to keep

- **Commits stay buildable and self-contained**, with the dependency and manifest entry alongside
  the code. Tag the last real commit of a phase; no empty marker commits.
- **Don't claim what hasn't been run.** The tags and commit messages distinguish verified from
  implemented, which is the only reason this document can be trusted about the board.
- **New runtime risk gets an instrumented test.** Every one of the five test classes exists because
  something compiled fine and would have failed on a device.
- Secrets live in `SettingsRepository` (DataStore, app-private), are masked in forms, and are never
  written into game history. `SettingsScreen` shows saved *keys*, never values.
