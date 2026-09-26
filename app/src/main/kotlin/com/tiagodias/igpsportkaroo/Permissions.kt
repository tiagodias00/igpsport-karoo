package com.tiagodias.igpsportkaroo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime BLE permissions by platform level. The SDK level is a parameter so the branching is unit-tested. */
object Permissions {
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context): List<String> =
        required(Build.VERSION.SDK_INT).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
