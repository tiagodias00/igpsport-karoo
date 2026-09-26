package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.CustomChange
import com.tiagodias.igpsportkaroo.protocol.CustomMode
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import com.tiagodias.igpsportkaroo.protocol.LightState

/**
 * The custom-mode editor as plain data: which slots, which patterns, which
 * sliders. The editor screen only draws it and sends [changeFor]'s changes to the session.
 */
data class CustomEditor(
    val slots: List<Slot>,
    /** The slot being edited; null when the light declares none. */
    val slot: Int?,
    /** The slot's config hasn't arrived yet: show "Reading…" and no controls. */
    val reading: Boolean,
    val patterns: List<PatternChoice>,
    val sliders: List<Slider>,
    val canPreview: Boolean,
    /**
     * A snapshot of this slot exists, the slot differs from it, and every write back to it is in the app's ranges (the light
     * stores anything, e.g. 101 %, and such a snapshot can't be restored).
     */
    val canRestore: Boolean,
) {
    data class Slot(val mode: Int, val label: String)
    data class PatternChoice(val subtype: Int, val label: String, val selected: Boolean)
    data class Slider(val key: Key, val label: String, val range: IntRange, val value: Int, val unit: String)

    sealed interface Key {
        data class Brightness(val lightNum: Int) : Key
        data object Cycle : Key
        data object Ratio : Key
    }

    companion object {
        /** Below this a mode looks off while the light reports it on (plan decision D4). */
        const val MIN_BRIGHTNESS = 5
        private val BRIGHTNESS = MIN_BRIGHTNESS..CustomMode.PCT.last

        private val PATTERN_LABELS = mapOf(CustomMode.STEADY to "Steady", CustomMode.FLASH to "Flash", CustomMode.BREATH to "Breath")
        private val CHANNEL_LABELS = mapOf(CustomMode.MAIN to "Main light", CustomMode.LOW_BEAM to "Low beam", CustomMode.HIGH_BEAM to "High beam")

        fun from(state: LightState, requestedSlot: Int?, original: CustomModeConfig?): CustomEditor {
            val modes = state.declaredModes.keys.filter { it in CustomMode.SLOTS }
            val slot = requestedSlot?.takeIf { it in modes } ?: modes.firstOrNull()
            // Not known (the selected pattern's data is missing): the same as not read yet.
            val config = slot?.let { state.customModes[it] }?.takeIf { it.known }
            // A pattern the protocol doesn't define gets no controls: every write to it would be rejected.
            val active = config?.active?.takeIf { it.subtype in CustomMode.SUBTYPES }
            val lights = active?.lights.orEmpty().distinctBy { it.lightNum }.sortedByDescending { it.lightNum }
            val sliders = buildList {
                lights.forEach { light ->
                    val label = if (lights.size == 1) "Brightness" else CHANNEL_LABELS[light.lightNum] ?: "Light ${light.lightNum}"
                    add(Slider(Key.Brightness(light.lightNum), label, BRIGHTNESS, light.pct.coerceIn(BRIGHTNESS), "%"))
                }
                if (active != null && active.subtype == CustomMode.FLASH) {
                    add(Slider(Key.Cycle, "Cycle", CustomMode.CYCLE_SECONDS, (active.cycleSeconds ?: 0).coerceIn(CustomMode.CYCLE_SECONDS), " s"))
                    add(Slider(Key.Ratio, "Lighting time", CustomMode.RATIO_PERCENT, (active.ratioPercent ?: 0).coerceIn(CustomMode.RATIO_PERCENT), "%"))
                }
            }
            return CustomEditor(
                slots = modes.map { Slot(it, "CUSTOM ${CustomMode.number(it)}") },
                slot = slot,
                reading = slot != null && config == null,
                patterns = config?.patterns.orEmpty().filter { it.subtype in CustomMode.SUBTYPES }.sortedBy { it.subtype }
                    .map { PatternChoice(it.subtype, PATTERN_LABELS[it.subtype] ?: "Pattern ${it.subtype}", it.subtype == config?.selected) },
                sliders = sliders,
                canPreview = state.connected && config != null,
                canRestore = state.connected && config != null && original != null && original.mode == slot && restorable(config, original),
            )
        }

        /**
         * The write for moving slider [key] to [value] (clamped into the slider's range) on [config]'s selected pattern.
         * Null (nothing to send) when [key] is not a slider of that pattern, e.g. a flash slider still on screen just
         * after a switch to steady: the light stores whatever it is sent, and a restore can't take such a value away.
         */
        fun changeFor(config: CustomModeConfig, key: Key, value: Int): CustomChange? {
            val active = config.active?.takeIf { it.subtype in CustomMode.SUBTYPES } ?: return null
            return when (key) {
                is Key.Brightness -> CustomChange.Brightness(active.subtype, key.lightNum, value.coerceIn(BRIGHTNESS))
                    .takeIf { active.lights.any { it.lightNum == key.lightNum } }
                Key.Cycle -> CustomChange.Cycle(active.subtype, value.coerceIn(CustomMode.CYCLE_SECONDS)).takeIf { active.subtype == CustomMode.FLASH }
                Key.Ratio -> CustomChange.Ratio(active.subtype, value.coerceIn(CustomMode.RATIO_PERCENT)).takeIf { active.subtype == CustomMode.FLASH }
            }
        }

        /** Something differs, and every write back to [original] builds a frame (the same check the session makes). */
        private fun restorable(config: CustomModeConfig, original: CustomModeConfig): Boolean {
            val changes = config.changesTo(original)
            if (changes.isEmpty()) return false
            return try {
                changes.forEach { IgpsProtocol.modifyCustomMode(config.mode, it) }
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}
