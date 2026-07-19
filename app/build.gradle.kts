import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        jniLibs {
            // JNA ships libjnidispatch.so; our own native chess library lives in src/main/jniLibs.
            useLegacyPackaging = false
        }
        resources {
            // The `simplechess` JAR bundles native binaries as JAR *resources* so that a desktop
            // JVM can extract them to a temp dir at runtime. That strategy does not work on
            // Android: the linker refuses to dlopen from an app-writable temp directory.
            //
            // We therefore ship the .so files in src/main/jniLibs/<abi>/ (so they land in the
            // app's nativeLibraryDir) and strip the JAR-resource copies here. Excluding them is
            // not merely a size optimisation - it makes the library's extraction step fail fast
            // and fall back to loading from the system path, which is the path that works.
            excludes += "/native/**"
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
    // --- SCP core (consumed from the sibling repo via composite build) ---
    implementation("scp:protocol:0.1.0")

    // Both of these reach `simplechess`, which declares the *desktop* JNA jar as an api
    // dependency (and pulls it in again via jna-platform). That jar carries no libjnidispatch.so
    // for Android and collides class-for-class with the Android AAR, so it is excluded from both
    // subtrees. These exclusions must be per-dependency: a configuration-wide exclude would also
    // drop the directly-declared AAR below, leaving no JNA on the classpath at all.
    implementation("scp:orchestrator:0.1.0") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("scp:game-controller:0.1.0") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    // JNA, Android variant: unlike the desktop jar, this AAR ships libjnidispatch.so per ABI.
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // --- Coroutines / AndroidX ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Instrumented tests (they must run on a device: the point is the native library) ---
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
