# Smart Chess (Android)

Android application for the Simple Chess smart board. The physical board is a **dumb sensor**: it
reports the colour occupying each of its 64 squares over BLE and nothing more. This app supplies
everything else — the rules, move inference, clocks, engine and online play.

## Architecture

The app is a client of the [Simple Chess Protocol](../smart-chess-protocol) (the specification).
The protocol's module implementations live in [`smart-chess-scp`](https://github.com/nachogoro/smart-chess-scp),
carried here as a git submodule; every one of them is reused unchanged, and only two modules are
Android-specific:

| SCP module | Where it comes from |
|---|---|
| Board | **This app** (`ble/BleBoardModule`) — reads the physical board over BLE |
| UI | **This app** (`ui/AndroidUIModule`) — Compose |
| Orchestrator, Game Controller, Engine adapters, Remote adapters | `smart-chess-scp`, unchanged |

The SCP modules are consumed as a Gradle **composite build**, so edits to them are picked up
immediately without publishing, wherever the checkout resolves from. See **Building** below and
`settings.gradle.kts`.

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

The rules engine is a C library reached through JNA. On Android the app consumes the turnkey
**`io.github.nachogoro:simplechess-android`** AAR (from Maven Central), which bundles the C library's
`.so` for every ABI and the JNA AAR (`libjnidispatch.so`). So the app does **no** native setup of
its own — no committed `jniLibs`, no separate JNA dependency, no resource stripping. The AAR delivers
those into the APK's `nativeLibraryDir`, the only place Android's linker will load them from.

The one wrinkle the app handles: the scp modules compile against the plain `simplechess` **jar** and
pull it in transitively. That jar carries the same classes as the AAR, so it is excluded build-wide
in `app/build.gradle.kts` to avoid duplicate classes — safe to do config-wide because the AAR is a
different module (`simplechess-android`), and it also drops the desktop JNA the jar depended on.

The app requires **`simplechess` 1.1.0 or newer**: 1.0.0 froze native pointers through
`java.lang.ref.Cleaner` (API 33+), throwing `NoClassDefFoundError` on older Android; 1.1.0 uses a
`PhantomReference`-based replacement. Core library desugaring does *not* fix the 1.0.0 problem —
`desugar_jdk_libs` covers no `java.lang.ref` classes.

`NativeChessLibraryTest` (instrumented) plays real moves through the engine and is the check that
the native library actually loads and runs. It must run on a device or emulator — and on one below
API 33, or it cannot catch a `Cleaner` regression.

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

This app consumes the SCP modules as a Gradle **composite build**. They are carried as a git
submodule, so this repo builds on its own:

```sh
git clone --recurse-submodules git@github.com:nachogoro/smart-chess-android.git
cd smart-chess-android
```

If you cloned without `--recurse-submodules`, run `git submodule update --init`.

The submodule is a normal checkout with its own remote: edits under `smart-chess-scp/` are picked
up by the next build, and you commit and push them from inside that directory like any other repo.
The commit the submodule points at is the SCP revision this app is known to build against — bump it
deliberately with `git add smart-chess-scp` here once the change is pushed there.

### Sharing one checkout with the desktop app

Both apps consume the same modules, so a workspace holding both is better served by a single
checkout — one edit then reaches both, rather than having to be made twice. Put it beside this repo
and it wins over the submodule automatically:

```
<parent>/
├── smart-chess-android    # this repo
├── smart-chess-desktop
└── smart-chess-scp        # takes precedence over the submodule
```

Anywhere else, point at it with `-PscpDir=<path>` (absolute or relative), or set `scpDir` in
`gradle.properties` to make it permanent. Every build prints which checkout it resolved, because a
sibling silently shadowing the submodule is otherwise easy to forget.

The native rules library (`io.github.nachogoro:simplechess`) comes from Maven Central, pulled in
transitively by the scp modules — no checkout of it is needed. Then:

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

## License

Copyright (C) 2026 Nacho de Gorostidi

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE) for the full text.

The app bundles the [Carballo](https://github.com/albertoruibal/carballo) chess engine through `smart-chess-scp`, and Carballo is licensed under the GPL-3.0, so the app is distributed under the same license.
