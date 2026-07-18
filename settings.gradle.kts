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

// The SCP modules (protocol, orchestrator, game controller, engine and remote adapters) live in
// the sibling simple-chess-protocol repo and are consumed as a Gradle composite build, so edits
// there are picked up immediately without publishing.
//
// Substitutions are declared explicitly rather than relying on auto-detection, because the scp
// build assigns `group` inside a `subprojects {}` block, which is not always visible to Gradle's
// automatic coordinate discovery.
//
// Only the modules the app actually depends on are substituted; the engine and remote adapters
// are added here as the phases that need them land.
includeBuild("../simple-chess-protocol/scp") {
    dependencySubstitution {
        substitute(module("scp:protocol")).using(project(":protocol"))
        substitute(module("scp:orchestrator")).using(project(":orchestrator"))
        substitute(module("scp:game-controller")).using(project(":game-controller"))
    }
}
