# Smart Chess (Android)

Android application for the Simple Chess smart board. The physical board is a **dumb sensor**: it
reports the colour occupying each of its 64 squares over BLE and nothing more. This app supplies
everything else — the rules, move inference, clocks, engine and online play.

## Architecture

The app is a client of the [Simple Chess Protocol](../smart-chess-protocol) (the specification).
The protocol's module implementations live in the sibling [`smart-chess-scp`](../smart-chess-scp)
repo; every one of them is reused unchanged, and only two modules are Android-specific:

| SCP module | Where it comes from |
|---|---|
| Board | **This app** (`ble/BleBoardModule`) — reads the physical board over BLE |
| UI | **This app** (`ui/AndroidUIModule`) — Compose |
| Orchestrator, Game Controller, Engine adapters, Remote adapters | `smart-chess-scp`, unchanged |

The SCP modules are consumed as a Gradle **composite build**, so edits in smart-chess-scp are
picked up immediately without publishing. See `settings.gradle.kts`.

```
Physical board ──BLE──> BleBoardModule ──┐
                                          ├──> Orchestrator ──> Game Controller ──> native rules engine
              AndroidUIModule (Compose) ──┘                └──> Engine / Remote adapters
```

## How a move is made

The board cannot tell a knight from a bishop, and it has no buttons. So:

1. The player moves the pieces on the physical board.
2. The player **taps the screen**, which is the equivalent of pressing a chess clock.
3. The app reads the 64 squares over BLE and the orchestrator infers which legal move produces
   that arrangement.
4. If the arrangement is ambiguous (two legal moves look identical) or a promotion happened, the
   app asks.

Because the board is the input device, an on-screen board would be redundant — it is available
behind an optional toggle purely so a player can confirm the two are in sync.

## Native chess library

The rules engine is a C library reached through JNA (`io.github.nachogoro:simplechess`, built from
the sibling `simple-chess-kotlin` checkout — see below). Getting it onto Android needs four things
that are easy to get wrong:

1. **`jniLibs/<abi>/libsimple-chess-games-c.so`** — the binaries are committed under
   `app/src/main/jniLibs/`. Android's linker will only `dlopen` from the app's `nativeLibraryDir`,
   which is where these end up.
2. **The JNA *AAR*, not the JAR** — only the AAR ships `libjnidispatch.so`. The desktop JAR arrives
   transitively via `simplechess` and is excluded per-dependency in `app/build.gradle.kts`.
3. **Stripping `native/**` from packaged resources** — the library otherwise tries to extract its
   bundled `.so` to a temp directory and load from there, which Android forbids. Removing those
   resources makes it fall through to the system path, which works.
4. **No `java.lang.ref.Cleaner`** — the published `simplechess` 1.0.0 frees native pointers through
   `Cleaner`, which is API 33+, so it throws `NoClassDefFoundError` on anything older. The wrapper
   uses a `PhantomReference`-based replacement instead, and is consumed from the sibling
   `simple-chess-kotlin` checkout via composite build until that reaches Maven Central. Core
   library desugaring does *not* fix this: `desugar_jdk_libs` covers no `java.lang.ref` classes.

`NativeChessLibraryTest` (instrumented) plays real moves through the engine and is the check that
all four are still correct. It must run on a device or emulator — and on one below API 33, or it
cannot catch a `Cleaner` regression.

## Engine play

Two engines are registered. **Carballo** (`scp:engine-carballo`) runs on the phone itself, so it
needs no network and no API key — it is listed first for that reason. **Remote Stockfish**
(`scp:engine-remote-stockfish`) plays over chess-api.com: stronger, but only while there is a
connection. With more than one engine registered the app asks which to play; with one, the
orchestrator picks it without asking.

Neither engine's options are hardcoded in the UI. Each adapter declares them as
`ParameterDescriptor`s and `ParameterForm` renders them generically, including `visible_when`
conditional fields such as Carballo's target Elo, which appears only once strength limiting is on.
Adding the second engine bore that out: it touched the two Gradle files and one line of
`ChessViewModel`, and no UI file at all.

Three instrumented tests cover engine play, and each matters because none of these failures show up
at compile time. `CarballoEngineTest` checks the on-device engine runs under ART and loads its
opening book from a classpath resource, which desktop-oriented libraries often get wrong on Android.
`RemoteStockfishEngineTest` does the same for the networked engine against a mock server, so it
needs no real service. `EngineGameFlowTest` plays a real game through the actual orchestrator with a
fake board standing in for BLE, and checks that with two engines registered the app asks which to
play and routes to the one chosen.

## Online play

Games are played through the Lichess Board API (`scp:remote-lichess`). Setup asks for two separate
parameter sets, which the adapter declares and `ParameterForm` renders: connection settings (the
API token, masked because the descriptor marks it `sensitive`) and seek settings (time control,
rated or casual, colour, or a specific opponent). They are persisted under separate keys, because
the token is entered once and kept while a seek merely defaults to last time's.

Two things behave differently from a local game:

- **The server owns the clocks.** The game is created with `Authority.EXTERNAL`, so no flag-fall
  timer runs locally and clock values only change when the server sends new ones — which happens on
  the opponent's move. The on-screen countdown between updates is cosmetic; the displayed time
  drifts slightly in your favour after your own move and snaps back when the server next reports.
  Running to zero on screen means nothing: the server decides, and a timeout arrives as a normal
  game-ended notification.
- **Withdrawing a seek is not the same as cancelling a prompt.** Once seeking, the orchestrator has
  finished its setup flow and is waiting on the platform, so there is no outstanding request to
  cancel. Backing out re-requests a new game, which is the orchestrator's documented path for
  withdrawing the seek and returning to idle — without it the seek stays live on Lichess after the
  user has walked away.

Cleartext HTTP is blocked, as it should be for something carrying an API token. Debug builds carry
a loopback-only exemption so the instrumented tests can use a mock server; release builds do not.

## Games history

Finished games are stored in Room. They are recorded the moment the orchestrator reports a game
over, not when the user dismisses the result dialog, so a game survives the app being killed at the
board. That persistence is driven by an explicit `gameEnded` flow on `AndroidUIModule` rather than
by observing its Compose state: outside a composition, snapshot observation only fires if something
pumps snapshot notifications, and losing games to that subtlety is not a risk worth taking.

Each stored move keeps the position that followed it, which the protocol already provides. Replay
is therefore just indexing into that list — no rules engine is involved in stepping through a game,
and the board view from local play is reused to draw it.

`toPgn` renders the export format: the seven mandatory tags in order, a blank line, then movetext
wrapped at 80 columns and terminated by the result, so other chess software can import it.

## Building

This app does not build in isolation. It consumes two sibling repositories as Gradle **composite
builds**, referenced by relative path (`../smart-chess-scp`, `../simple-chess-kotlin` in
`settings.gradle.kts`), so all three must be checked out side by side under the same parent
directory:

```
<parent>/
├── smart-chess-android    # this repo
├── smart-chess-scp        # the SCP module implementations
└── simple-chess-kotlin     # the JNA wrapper over the native rules library
```

```sh
git clone git@github.com:nachogoro/smart-chess-android.git
git clone git@github.com:nachogoro/smart-chess-scp.git
git clone git@github.com:nachogoro/simple-chess-kotlin.git
cd smart-chess-android
```

`simple-chess-kotlin` is built from source rather than taken from Maven Central because the
published `simplechess` 1.0.0 frees native pointers through `java.lang.ref.Cleaner`, which is API
33+ (see "Native chess library" above). Then:

```sh
# Requires an Android SDK; see local.properties
./gradlew :app:assembleDebug

# The native-library check (needs a running device/emulator)
./gradlew :app:connectedDebugAndroidTest
```

## Permissions

BLE permissions differ sharply by version, and a missing one makes scanning fail *silently*:

- **Android 12+**: `BLUETOOTH_SCAN` (declared `neverForLocation`) and `BLUETOOTH_CONNECT`.
- **Android 11 and below**: `ACCESS_FINE_LOCATION`, and the system location toggle must be on.

The connection screen checks each requirement separately and prompts for whichever is missing.

## Status

- [x] Local player vs local player
- [x] Play against an engine (Carballo on-device, or remote Stockfish)
- [x] Play remotely (Lichess) — not yet exercised against the live service
- [x] Games history with PGN and move-by-move replay
