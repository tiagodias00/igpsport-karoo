package com.tiagodias.igpsportkaroo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.SystemNotification
import timber.log.Timber

class IgpsExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (!connected) return@connect
            Timber.i("Karoo system connected; requesting Bluetooth")
            // Without this the Karoo may power the radio down when it has no sensors of its own.
            karooSystem.dispatch(RequestBluetooth(extension))
            if (Permissions.missing(applicationContext).isNotEmpty()) {
                karooSystem.dispatch(
                    SystemNotification(
                        id = "igps-permissions",
                        message = getString(R.string.perm_needed),
                        action = getString(R.string.open_settings),
                        actionIntent = SETTINGS_ACTION,
                    ),
                )
            }
        }
    }

    override fun onDestroy() {
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "igpsport"
        const val SETTINGS_ACTION = "com.tiagodias.igpsportkaroo.SETTINGS"
        const val CONTROL_ACTION = "com.tiagodias.igpsportkaroo.CONTROL"
    }
}
