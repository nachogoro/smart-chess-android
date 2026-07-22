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
includeBuild("../smart-chess-scp") {
    dependencySubstitution {
        substitute(module("scp:scp-core")).using(project(":scp-core"))
        substitute(module("scp:orchestrator")).using(project(":orchestrator"))
        substitute(module("scp:game-controller")).using(project(":game-controller"))
        substitute(module("scp:engine-carballo")).using(project(":engine-carballo"))
        substitute(module("scp:remote-lichess")).using(project(":remote-lichess"))
        substitute(module("scp:engine-remote-stockfish")).using(project(":engine-remote-stockfish"))
    }
}
