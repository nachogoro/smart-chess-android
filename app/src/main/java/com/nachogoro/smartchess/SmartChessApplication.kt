package com.nachogoro.smartchess

import android.app.Application

class SmartChessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        configureNativeLibraryPath()
    }

    /**
     * Points JNA at the app's native library directory.
     *
     * The `simplechess` library ships its native binaries as JAR resources and, on a desktop JVM,
     * extracts them to a temp directory at startup. Android's dynamic linker refuses to `dlopen`
     * a library from an app-writable directory, so that strategy cannot work here.
     *
     * Instead the `.so` files are packaged under `src/main/jniLibs/<abi>/`, which the installer
     * unpacks into [android.content.pm.ApplicationInfo.nativeLibraryDir] - the one location the
     * linker will load from. Setting `jna.library.path` here makes JNA look there first.
     *
     * The JAR-resource copies are stripped from the APK (see the `packaging` block in
     * build.gradle.kts), so the library's extraction step fails fast and falls through to this
     * path rather than pointing JNA at an unusable temp directory.
     */
    private fun configureNativeLibraryPath() {
        System.setProperty("jna.library.path", applicationInfo.nativeLibraryDir)
        System.setProperty("jna.nosys", "false")
    }
}
