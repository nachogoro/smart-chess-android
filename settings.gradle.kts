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

// The SCP modules (scp-core, orchestrator, game controller, engine and remote adapters) are
// consumed as a Gradle composite build, so edits to them are picked up immediately without
// publishing.
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
// The checkout is looked for in three places, in order: an explicit -PscpDir (or the same property
// in gradle.properties), a sibling checkout, and the bundled submodule. The sibling wins over the
// submodule so that in a workspace holding both apps one edit still reaches both of them; the
// submodule is what makes a clone of this repo alone enough to build.
//
// The probe is for settings.gradle.kts rather than the directory, because `git clone` without
// --recurse-submodules leaves the submodule path behind as an empty directory. That passes a
// directory check and then fails much later as an unresolvable `scp:*` coordinate. Nothing
// publishes the scp modules, so those coordinates resolve through the substitutions below and
// nowhere else, and the failure reads as a broken repository rather than a missing checkout.
val scpDir = (providers.gradleProperty("scpDir").map { settingsDir.resolve(it) }.orNull
    ?: settingsDir.resolve("../smart-chess-scp").takeIf { it.isDirectory }
    ?: settingsDir.resolve("smart-chess-scp")).canonicalFile
require(scpDir.resolve("settings.gradle.kts").isFile) {
    "smart-chess-scp not found at $scpDir - run `git submodule update --init`, " +
        "or pass -PscpDir=<path>."
}
logger.lifecycle("Using smart-chess-scp from $scpDir")

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
