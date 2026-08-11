import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nachogoro.smartchess"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nachogoro.smartchess"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the ABIs for which we ship the native chess library.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- SCP modules (from the sibling smart-chess-scp repo via composite build) ---
    implementation("scp:scp-core:0.1.0")
    implementation("scp:orchestrator:0.1.0")
    implementation("scp:game-controller:0.1.0")
    implementation("scp:engine-carballo:0.1.0")
    implementation("scp:engine-remote-stockfish:0.1.0")
    implementation("scp:remote-lichess:0.1.0")

    // The native rules library, Android packaging. This AAR bundles the C library's .so and the JNA
	// AAR (libjnidispatch.so), so the app needs no manual native setup. The
	// plain jar they pull transitively is dropped (see below) to avoid
	// duplicating those classes.
    implementation("io.github.nachogoro:simplechess-android:2.0.0")

    // --- Coroutines / AndroidX ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // For per-app language selection. AppCompat backports Android 13's locale API below 33 and
    // persists the choice itself, so the app keeps no copy of it to fall out of step.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Games history ---
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Local tests (no device: these cover logic that touches neither Android nor the native
    //     library, such as turning a move into the sentence that is read out loud) ---
    testImplementation("junit:junit:4.13.2")

    // --- Instrumented tests (they must run on a device: the point is the native library) ---
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Lets the Lichess HTTP layer be exercised under ART without a Lichess account.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Direct access to the raw simplechess Game API for the native tests. The AAR (not the jar,
    // which is excluded below) so the test resolves the same artifact the app ships.
    androidTestImplementation("io.github.nachogoro:simplechess-android:2.0.0")
}

// The scp modules pull the plain `simplechess` jar transitively; the simplechess-android AAR above
// carries the same classes plus the native libraries, so the jar is dropped everywhere to avoid
// duplicate classes at dex time. A configuration-wide exclude is safe here (unlike for JNA) because
// the AAR is a different module - `simplechess-android`, not `simplechess` - so it is untouched.
// Excluding the jar also drops the desktop JNA it depended on; JNA arrives as the AAR's dependency.
configurations.all {
    exclude(group = "io.github.nachogoro", module = "simplechess")
}
