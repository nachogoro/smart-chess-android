# Smart Chess (Android)

Android application for the Simple Chess smart board. The physical board is a **dumb sensor**: it
reports the colour occupying each of its 64 squares over BLE and nothing more. This app supplies
everything else — the rules, move inference, clocks, engine and online play.

## Architecture

The app is a client of the [Simple Chess Protocol](../simple-chess-protocol). Every SCP module is
reused unchanged from that repo; only two modules are Android-specific:

| SCP module | Where it comes from |
|---|---|
| Board | **This app** (`ble/BleBoardModule`) — reads the physical board over BLE |
| UI | **This app** (`ui/AndroidUIModule`) — Compose |
| Orchestrator, Game Controller, Engine adapters, Remote adapters | `simple-chess-protocol/scp`, unchanged |

The SCP modules are consumed as a Gradle **composite build**, so edits in the protocol repo are
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

## Building

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
- [ ] Play against an engine
- [ ] Play remotely
- [ ] Games history with PGN and move-by-move replay
