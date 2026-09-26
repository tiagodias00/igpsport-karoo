package com.tiagodias.igpsportkaroo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** A Karoo extension is a service with no UI, so the BLE permissions must be requested here. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun updateStatus() {
        status.setText(if (Permissions.missing(this).isEmpty()) R.string.perm_granted else R.string.perm_needed)
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
