pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }
}

rootProject.name = "smart-chess-android-app"

include(":app")

// The SCP modules (scp-core, orchestrator, game controller, engine and remote adapters) live in
// the sibling smart-chess-scp repo and are consumed as a Gradle composite build, so edits there
// are picked up immediately without publishing.
//
// Substitutions are declared explicitly rather than relying on auto-detection, because the scp
// build assigns `group` inside a `subprojects {}` block, which is not always visible to Gradle's
// automatic coordinate discovery.
//
// The native rules engine (io.github.nachogoro:simplechess) is consumed from Maven Central, pulled
// in transitively by the scp modules at the version they declare. It used to be composite-built
// from ../simple-chess-kotlin because the published 1.0.0 froze native pointers through
// java.lang.ref.Cleaner (API 33+); 1.1.0 replaced that, so the published artifact works on Android.
//
// The location is checked before use, and overridable with -PscpDir. Nothing publishes the scp
// modules, so `scp:*` coordinates resolve through the substitutions below and nowhere else: without
// this check a missing sibling surfaces as a failure to resolve an artifact that exists in no
// repository, which reads as a broken repository rather than a missing checkout.
val scpDir = settingsDir.resolve(providers.gradleProperty("scpDir").getOrElse("../smart-chess-scp"))
require(scpDir.isDirectory) {
    "smart-chess-scp not found at $scpDir - clone it beside this repo, or pass -PscpDir=<path>."
}

includeBuild(scpDir) {
    dependencySubstitution {
        substitute(module("scp:scp-core")).using(project(":scp-core"))
        substitute(module("scp:orchestrator")).using(project(":orchestrator"))
        substitute(module("scp:game-controller")).using(project(":game-controller"))
        substitute(module("scp:engine-carballo")).using(project(":engine-carballo"))
        substitute(module("scp:remote-lichess")).using(project(":remote-lichess"))
        substitute(module("scp:engine-remote-stockfish")).using(project(":engine-remote-stockfish"))
    }
}
