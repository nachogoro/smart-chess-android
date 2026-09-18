package com.nachogoro.smartchess.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Runtime permission requirements for BLE, which changed substantially in Android 12.
 *
 * - API 31+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. Without them, scanning fails silently.
 * - API 30 and below: `ACCESS_FINE_LOCATION`, and location services must actually be switched on,
 *   because a BLE scan was historically treated as a means of inferring location.
 */
object BlePermissions {

    val required: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context): List<String> = required.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * Whether the OS-level location toggle must be on for scanning to return results.
     *
     * Only relevant below API 31; from Android 12 onwards a `neverForLocation` scan is exempt.
     */
    fun requiresLocationServices(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun locationServicesEnabled(context: Context): Boolean {
        if (!requiresLocationServices()) return true
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }
}
