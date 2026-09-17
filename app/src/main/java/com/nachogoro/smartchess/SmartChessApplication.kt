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
     * The `.so` files are packaged under `src/main/jniLibs/<abi>/`, which the installer
     * unpacks into [android.content.pm.ApplicationInfo.nativeLibraryDir]. Setting
     `jna.library.path` here makes JNA look there first.
     */
    private fun configureNativeLibraryPath() {
        System.setProperty("jna.library.path", applicationInfo.nativeLibraryDir)
        System.setProperty("jna.nosys", "false")
    }
}
