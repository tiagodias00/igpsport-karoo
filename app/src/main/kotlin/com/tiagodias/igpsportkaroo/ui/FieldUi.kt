package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.SmartConfig

/**
 * Pure view model of the ride fields: four fixed buttons (SOLID / FLASH / AUTO / OFF) + a battery/run-time
 * footer, plus the compact field's one-cell status ([statusShort]) and each button's short texts.
 */
data class FieldUi(val buttons: List<Button>, val footer: String, val connected: Boolean, val statusShort: String) {
    enum class Kind { SOLID, FLASH, AUTO, OFF }

    /** [label] over [detail] on the regular fields; [shortLabel] + [shortDetail] on the compact one. */
    data class Button(
        val kind: Kind,
        val label: String,
        val detail: String,
        val active: Boolean,
        val available: Boolean,
        val shortLabel: String,
        val shortDetail: String,
    ) {
        /** "SOL HI" when that fits in [maxChars], else just the short label. */
        fun compactText(maxChars: Int): String {
            val full = if (shortDetail.isEmpty()) shortLabel else "$shortLabel $shortDetail"
            return if (full.length <= maxChars) full else shortLabel
        }
    }

    /** While the light is not connected, tapping the footer forces an immediate reconnect. */
    val reconnectable: Boolean get() = !connected

    companion object {
        fun from(state: LightState): FieldUi {
            val buttons = listOf(solid(state), flash(state), auto(state), off(state))
            val battery = state.batteryPercent?.let { "$it%" }
            val footer = when {
                !state.connected -> "Searching for light… tap to retry"
                state.poweredOff -> listOfNotNull("Light off", battery).joinToString(SEPARATOR)
                state.outputOff && state.autoLightOn -> listOfNotNull("Auto off (daylight)", battery).joinToString(SEPARATOR)
                else -> listOfNotNull(battery, state.remainingMinutes?.let { "${formatMinutes(it)} left" })
                    .joinToString(SEPARATOR).ifEmpty { "Connected" }
            }
            // Compact status cell: battery while connected (the OFF button already shows "off"), a retry glyph otherwise.
            val statusShort = if (state.connected) battery ?: "--" else "↻"
            return FieldUi(buttons, footer, state.connected, statusShort)
        }

        fun formatMinutes(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

        private const val SEPARATOR = "  ·  "

        /** On, and in a manual mode: the light keeps reporting its remembered mode while off or on auto. */
        private fun manual(state: LightState) = state.connected && !state.poweredOff && !state.autoLightOn

        /** Unknown declared modes count as available, as before: the tap then uses whatever is known by then. */
        private fun hasLevels(state: LightState, levels: (List<Int>) -> List<Int>) =
            state.declaredModes.isEmpty() || levels(state.enabledModes).isNotEmpty()

        private fun solid(state: LightState): Button {
            val level = state.steadyLevel
            return Button(
                kind = Kind.SOLID,
                label = "SOLID",
                detail = level?.let(LightModes::label).orEmpty(),
                active = manual(state) && state.mode in LightModes.STEADY,
                available = hasLevels(state, LightModes::steadyLevels),
                shortLabel = "SOL",
                shortDetail = level?.let(LightModes::shortLabel).orEmpty(),
            )
        }

        private fun flash(state: LightState): Button {
            val level = state.flashLevel?.let(LightModes::shortLabel)
            return Button(
                kind = Kind.FLASH,
                label = "FLASH",
                detail = level.orEmpty(),
                active = manual(state) && state.mode in LightModes.FLASHING,
                available = hasLevels(state, LightModes::flashLevels),
                shortLabel = "FLS",
                shortDetail = level?.removePrefix("FL ").orEmpty(),
            )
        }

        private fun auto(state: LightState): Button {
            val brightness = state.autoBrightnessPercent?.let { "$it%" }
            return Button(
                kind = Kind.AUTO,
                label = "AUTO",
                detail = when {
                    state.outputOff -> "off (day)"
                    brightness != null -> brightness
                    else -> state.mode?.let(LightModes::label).orEmpty()
                },
                active = state.connected && !state.poweredOff && state.autoLightOn,
                available = state.smartConfigs.isEmpty() || SmartConfig.AUTO_LIGHT in state.smartConfigs,
                shortLabel = "AUTO",
                shortDetail = when {
                    state.outputOff -> "day"
                    brightness != null -> brightness
                    else -> state.mode?.let(LightModes::shortLabel).orEmpty()
                },
            )
        }

        private fun off(state: LightState) = Button(
            kind = Kind.OFF,
            label = "OFF",
            detail = "",
            active = state.connected && state.poweredOff,
            available = true,
            shortLabel = "OFF",
            shortDetail = "",
        )
    }
}
