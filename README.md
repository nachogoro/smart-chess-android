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

The rules engine is a C library reached through JNA (`io.github.nachogoro:simplechess`, from Maven
Central). Getting it onto Android needs four things that are easy to get wrong:

1. **`jniLibs/<abi>/libsimple-chess-games-c.so`** — the binaries are committed under
   `app/src/main/jniLibs/`. Android's linker will only `dlopen` from the app's `nativeLibraryDir`,
   which is where these end up.
2. **The JNA *AAR*, not the JAR** — only the AAR ships `libjnidispatch.so`. The desktop JAR arrives
   transitively via `simplechess` and is excluded per-dependency in `app/build.gradle.kts`.
3. **Stripping `native/**` from packaged resources** — the library otherwise tries to extract its
   bundled `.so` to a temp directory and load from there, which Android forbids. Removing those
   resources makes it fall through to the system path, which works.
4. **No `java.lang.ref.Cleaner`** — `simplechess` 1.0.0 froze native pointers through `Cleaner`,
   which is API 33+, so it threw `NoClassDefFoundError` on anything older. 1.1.0 uses a
   `PhantomReference`-based replacement, so this app depends on **1.1.0 or newer**. Core library
   desugaring does *not* fix the 1.0.0 problem: `desugar_jdk_libs` covers no `java.lang.ref` classes.

There is also a turnkey **`io.github.nachogoro:simplechess-android`** AAR that bundles the `.so` and
the JNA AAR, removing traps 1–3 for a consumer. This app does not use it yet — it keeps the manual
`jniLibs` setup — because the scp modules pull the plain `simplechess` jar transitively, and mixing
the two would duplicate classes. Switching fully to the AAR is possible but needs that dependency
untangled.

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

This app consumes the SCP modules from the sibling `smart-chess-scp` repository as a Gradle
**composite build** (relative path `../smart-chess-scp` in `settings.gradle.kts`), so the two must
be checked out side by side:

```
<parent>/
├── smart-chess-android    # this repo
└── smart-chess-scp        # the SCP module implementations
```

```sh
git clone git@github.com:nachogoro/smart-chess-android.git
git clone git@github.com:nachogoro/smart-chess-scp.git
cd smart-chess-android
```

The native rules library (`io.github.nachogoro:simplechess`) comes from Maven Central, pulled in
transitively by the scp modules — no sibling checkout of it is needed. Then:

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
