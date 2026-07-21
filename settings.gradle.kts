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

rootProject.name = "simple-chess-android-app"

include(":app")

// The SCP modules (scp-core, orchestrator, game controller, engine and remote adapters) live in
// the sibling simple-chess-scp repo and are consumed as a Gradle composite build, so edits there
// are picked up immediately without publishing.
//
// Substitutions are declared explicitly rather than relying on auto-detection, because the scp
// build assigns `group` inside a `subprojects {}` block, which is not always visible to Gradle's
// automatic coordinate discovery.
// The Kotlin wrapper around the native rules engine is likewise consumed from source rather than
// from Maven Central. The published 1.0.0 frees native pointers through java.lang.ref.Cleaner,
// which is API 33+ and so unusable here; building it from source picks up the replacement.
includeBuild("../simple-chess-kotlin") {
    dependencySubstitution {
        substitute(module("io.github.nachogoro:simplechess")).using(project(":"))
    }
}

includeBuild("../simple-chess-scp") {
    dependencySubstitution {
        substitute(module("scp:scp-core")).using(project(":scp-core"))
        substitute(module("scp:orchestrator")).using(project(":orchestrator"))
        substitute(module("scp:game-controller")).using(project(":game-controller"))
        substitute(module("scp:engine-carballo")).using(project(":engine-carballo"))
        substitute(module("scp:remote-lichess")).using(project(":remote-lichess"))
        substitute(module("scp:engine-remote-stockfish")).using(project(":engine-remote-stockfish"))
    }
}
