package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.LightState

/** Pure view model of the ride field: three mode buttons + a battery/run-time footer. */
data class FieldUi(val buttons: List<Button>, val footer: String, val connected: Boolean) {
    data class Button(val slot: Int, val label: String, val active: Boolean, val available: Boolean)

    /** While the light is not connected, tapping the footer forces an immediate reconnect. */
    val reconnectable: Boolean get() = !connected

    companion object {
        fun from(state: LightState, slotModes: List<Int>): FieldUi {
            val known = state.declaredModes.isNotEmpty()
            val buttons = slotModes.mapIndexed { slot, mode ->
                val isOff = mode == LightModes.OFF
                Button(
                    slot = slot,
                    label = LightModes.label(mode),
                    // The light keeps reporting its remembered mode while off, so "off" comes from state.poweredOff.
                    active = state.connected && if (isOff) state.poweredOff else !state.poweredOff && state.mode == mode,
                    available = isOff || !known || mode in state.declaredModes,
                )
            }
            val footer = if (!state.connected) {
                "Searching for light… tap to retry"
            } else if (state.poweredOff) {
                listOfNotNull("Light off", state.batteryPercent?.let { "$it%" }).joinToString("  ·  ")
            } else {
                listOfNotNull(
                    state.batteryPercent?.let { "$it%" },
                    state.remainingMinutes?.let { "${formatMinutes(it)} left" },
                ).joinToString("  ·  ").ifEmpty { "Connected" }
            }
            return FieldUi(buttons, footer, state.connected)
        }

        fun formatMinutes(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
