package com.tiagodias.igpsportkaroo.light

import com.tiagodias.igpsportkaroo.ble.LightLink
import com.tiagodias.igpsportkaroo.ble.LinkEvent
import com.tiagodias.igpsportkaroo.protocol.AutoDimTracker
import com.tiagodias.igpsportkaroo.protocol.CustomChange
import com.tiagodias.igpsportkaroo.protocol.CustomMode
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.FrameAssembler
import com.tiagodias.igpsportkaroo.protocol.Hex
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Turns link events into [LightState] and light commands into frames, for one paired light. [frameLog], when
 * set (debug builds), gets one line per received frame and what it parsed to.
 */
class LightSession(
    private val link: LightLink,
    private val address: String,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val frameLog: ((String) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(LightState())
    val state: StateFlow<LightState> = _state.asStateFlow()

    private val assembler = FrameAssembler()
    private val dimTracker = AutoDimTracker()
    private var job: Job? = null
    private val commandLock = Any()
    private var offCommandAt: Long? = null
    private var reconnecting = false
    private var lastReconnectAt: Long? = null

    fun start(): Unit = synchronized(commandLock) {
        if (job != null) return
        job = scope.launch { runLink() }
    }

    fun stop(): Unit = synchronized(commandLock) {
        job?.cancel()
        job = null
        reconnecting = false
        dimTracker.reset()
        _state.value = LightState()
    }

    /**
     * Forces an immediate reconnect while the light is not connected: drops the current link flow (and its
     * backoff) and collects a fresh one, which starts a new search right away. The last known state is kept.
     * No-op while connected or stopped. Serialized with [start]/[stop] on [commandLock].
     *
     * One reconnect in flight at a time: a call is ignored while the previous reconnect is still waiting for the
     * old link's teardown (cancelling that wait would let a new link start before the teardown ran), and within
     * [RECONNECT_MIN_INTERVAL_MS] of the last accepted call (each one starts a scan; Android throttles scans).
     */
    fun reconnectNow(): Unit = synchronized(commandLock) {
        val previous = job ?: return
        if (_state.value.connected) return
        if (reconnecting) {
            Timber.d("Reconnect ignored: the previous one is still pending")
            return
        }
        val at = now()
        lastReconnectAt?.let { last ->
            if (at - last < RECONNECT_MIN_INTERVAL_MS) {
                Timber.d("Reconnect ignored: last one was %d ms ago", at - last)
                return
            }
        }
        lastReconnectAt = at
        reconnecting = true
        // Cancelled here, not in the new job, so a stop() before the new job runs still ends the old link.
        previous.cancel()
        job = scope.launch {
            // Wait for the old flow's teardown before connecting again, so it can't close the new connection.
            if (!previous.isCompleted) previous.join()
            synchronized(commandLock) { if (job === coroutineContext.job) reconnecting = false }
            runLink()
        }
    }

    private suspend fun runLink() = coroutineScope {
        var poller: Job? = null
        link.connect(address).collect { event ->
            when (event) {
                LinkEvent.Connected -> {
                    assembler.reset()
                    synchronized(commandLock) { _state.value = _state.value.copy(connected = true) }
                    refreshAll()
                    poller?.cancel()
                    poller = launch {
                        while (true) {
                            delay(pollIntervalMs)
                            link.send(IgpsProtocol.readBattery())
                            link.send(IgpsProtocol.readRemainingTime())
                        }
                    }
                }
                LinkEvent.Disconnected -> {
                    poller?.cancel()
                    synchronized(commandLock) {
                        // The next connection starts a fresh "full" reference: an old one might no longer apply.
                        dimTracker.reset()
                        _state.value = _state.value.copy(connected = false)
                    }
                }
                is LinkEvent.Fragment -> assembler.push(event.bytes).forEach { frame ->
                    val update = IgpsProtocol.parseFrame(frame)
                    frameLog?.invoke("rx ${Hex.encode(frame)} -> $update")
                    update?.let { u -> synchronized(commandLock) { _state.value = applyReport(_state.value, u) } }
                    // Each declared custom slot's config decides whether it is SOLID or FLASH and how it's labelled.
                    update?.declaredModes?.keys?.filter { it in CustomMode.SLOTS }?.forEach {
                        link.send(IgpsProtocol.readCustomMode(it))
                    }
                }
            }
        }
    }

    /**
     * Selects [mode] ([LightModes.OFF] switches the light off), enabling it on the light first if it is
     * declared but disabled. False when not connected.
     *
     * Thread-safe: calls are serialized on [commandLock], so one call's command sequence always finishes
     * (all its frames sent) before another's starts — command sequences never interleave on the wire.
     */
    fun selectMode(mode: Int): Boolean = synchronized(commandLock) {
        if (mode == LightModes.OFF) {
            if (!link.send(IgpsProtocol.setMode(LightModes.OFF))) return false
            // No read-back: the light would answer with its remembered mode, never 0.
            offCommandAt = now()
            _state.value = _state.value.copy(poweredOff = true)
            return true
        }
        if (_state.value.declaredModes[mode] == false) {
            if (!link.send(IgpsProtocol.setModeEnabled(mode, true))) return false
            link.send(IgpsProtocol.readSupportedModes())
        }
        if (!link.send(IgpsProtocol.setMode(mode))) return false
        offCommandAt = null
        // Optimistic: the field shows the new mode right away; the read-back below confirms or corrects it.
        // Applied like a report, so the last steady / flash level is remembered too.
        _state.value = _state.value.apply(LightUpdate(mode = mode)).copy(poweredOff = false).withAutoDimmed()
        // The light only ACKs writes: read the mode back so the UI shows what it really did.
        return link.send(IgpsProtocol.readCurrentMode())
    }

    /** Also serialized on [commandLock] (reentrant): the read of the current mode and the [selectMode] call
     * that follows it happen as one atomic step relative to other threads. */
    fun nextMode(): Boolean = synchronized(commandLock) {
        val s = _state.value
        val next = LightModes.next(if (s.poweredOff) null else s.mode, s.enabledModes) ?: return false
        selectMode(next)
    }

    /**
     * Switches smart config [id] ([SmartConfig]) on or off, then reads the configs back. The field shows the
     * new status right away. False when not connected. Serialized on [commandLock].
     */
    fun setSmartConfig(id: Int, on: Boolean): Boolean = synchronized(commandLock) {
        val status = if (on) SmartConfig.ON else SmartConfig.OFF
        if (!link.send(IgpsProtocol.setSmartConfig(id, status))) return false
        // Without auto light the output is never switched off for daylight, so a stale "output off" goes too.
        val clearsOutputOff = id == SmartConfig.AUTO_LIGHT && !on
        val current = _state.value
        _state.value = current.copy(
            smartConfigs = current.smartConfigs + (id to status),
            outputOff = current.outputOff && !clearsOutputOff,
        ).withAutoDimmed()
        return link.send(IgpsProtocol.readSmartConfigs())
    }

    /**
     * Writes one [change] to custom slot [mode], shows it at once and reads the slot back (the light only ACKs).
     * False when not connected, or while the slot's config is unknown (nothing to edit yet). Serialized on
     * [commandLock], like the other commands. The light validates nothing, so the caller clamps the values:
     * [IgpsProtocol.modifyCustomMode] rejects any outside the app's ranges.
     */
    fun changeCustomMode(mode: Int, change: CustomChange): Boolean = synchronized(commandLock) {
        val current = _state.value.customModes[mode] ?: return false
        if (!link.send(IgpsProtocol.modifyCustomMode(mode, change))) return false
        _state.value = _state.value.let { it.copy(customModes = it.customModes + (mode to current.applied(change))) }
        reselectIfPlaying(mode)
        return link.send(IgpsProtocol.readCustomMode(mode))
    }

    /**
     * Turns custom slot [target.mode] back into [target] with only the writes that differ (the pattern switch
     * last), then reads it back. True without sending anything when it already matches. False, also without
     * sending anything, when a write would carry a value outside the app's ranges (a snapshot the light stored
     * wrongly). Same rules as [changeCustomMode].
     */
    fun restoreCustomMode(target: CustomModeConfig): Boolean = synchronized(commandLock) {
        val current = _state.value.customModes[target.mode] ?: return false
        val changes = current.changesTo(target)
        if (changes.isEmpty()) return true
        // Every frame is built before the first is sent, so an invalid value can't leave a half-restored slot.
        val frames = try {
            changes.map { IgpsProtocol.modifyCustomMode(target.mode, it) }
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Not restoring custom mode %d", target.mode)
            return false
        }
        for (frame in frames) if (!link.send(frame)) return false
        val restored = changes.fold(current) { c, ch -> c.applied(ch) }
        _state.value = _state.value.let { it.copy(customModes = it.customModes + (target.mode to restored)) }
        reselectIfPlaying(target.mode)
        return link.send(IgpsProtocol.readCustomMode(target.mode))
    }

    /** Edits apply live on the VS1200S (docs/vs1200s-findings.md), so nothing to do. */
    private fun reselectIfPlaying(@Suppress("UNUSED_PARAMETER") mode: Int) = Unit

    /** SOLID: steady light, manual. Cycles the steady levels when already there, else returns to the last one. */
    fun selectSolid(): Boolean = selectManual({ it.isSteady(it.mode) }, { it.steadyLevels }) { it.steadyLevel }

    /** FLASH: the same as [selectSolid] for the flash levels (built-in flash modes, then custom slots that blink). */
    fun selectFlash(): Boolean = selectManual({ it.isFlashing(it.mode) }, { it.flashLevels }) { it.flashLevel }

    /** AUTO: switches auto light on, and the light itself on (current steady level) if we switched it off. */
    fun selectAuto(): Boolean = synchronized(commandLock) {
        val wasOff = _state.value.poweredOff
        if (!setSmartConfig(SmartConfig.AUTO_LIGHT, on = true)) return false
        if (!wasOff) return true
        val level = _state.value.steadyLevel ?: return true
        return selectMode(level)
    }

    /**
     * Goes to a mode of a group: the next of [levels] when the light is already in the group ([inGroup]; on,
     * manual), else [current] (the last one used, or the light's first). SOLID and FLASH are manual, so auto
     * light is switched off first. One atomic step on [commandLock].
     */
    private fun selectManual(
        inGroup: (LightState) -> Boolean,
        levels: (LightState) -> List<Int>,
        current: (LightState) -> Int?,
    ): Boolean = synchronized(commandLock) {
        val s = _state.value
        val cycling = !s.poweredOff && !s.autoLightOn && inGroup(s)
        val next = if (cycling) LightModes.next(s.mode, levels(s)) else null
        val target = next ?: current(s) ?: return false
        return selectManualMode(target)
    }

    /**
     * Selects [mode] as a manual mode: switches auto light off first when it is on, since the light otherwise
     * keeps driving its output itself and the mode change does nothing visible. One atomic step on [commandLock].
     */
    fun selectManualMode(mode: Int): Boolean = synchronized(commandLock) {
        // Switching auto light off also clears a daylight "output off": manual modes always light.
        if (_state.value.autoLightOn && !setSmartConfig(SmartConfig.AUTO_LIGHT, on = false)) return false
        selectMode(mode)
    }

    /**
     * Evidence that the light is on ends "off", unless it arrives within [OFF_SETTLE_MS] of our OFF command
     * (the light echoes its remembered mode right after it): a mode report (a button press, or the read-back
     * after a reconnect), or a spontaneous run-time state frame with a run time ([LightUpdate.outputOff] ==
     * false), which the light sends only while lit — switched back on by its own button it may send no mode
     * report at all. A polled run time is no evidence: the light answers it while off too.
     *
     * Callers must already hold [commandLock]: this reads [offCommandAt] and, via [withAutoDimmed], mutates
     * [dimTracker].
     */
    private fun applyReport(state: LightState, update: LightUpdate): LightState {
        val applied = state.apply(update)
        val settling = offCommandAt?.let { now() - it < OFF_SETTLE_MS } ?: false
        val lit = update.mode != null || update.outputOff == false
        val powered =
            if (state.poweredOff && lit && !settling) applied.copy(poweredOff = false) else applied
        return powered.withAutoDimmed()
    }

    /**
     * Recomputes [LightState.autoDimmed] from the current mode / auto light / remaining time via [dimTracker].
     *
     * Callers must already hold [commandLock] and must assign the result directly to `_state.value`, never
     * from inside a `_state.update { }` transform: [dimTracker] is mutable, and `update {}`'s compare-and-swap
     * retry loop can invoke its lambda more than once under contention, so a discarded attempt would still
     * mutate the tracker's reference and leave it transiently wrong.
     */
    private fun LightState.withAutoDimmed(): LightState =
        copy(autoDimmed = dimTracker.update(mode, autoLightOn, remainingMinutes))

    private fun refreshAll() {
        link.send(IgpsProtocol.readSupportedModes())
        link.send(IgpsProtocol.readCurrentMode())
        link.send(IgpsProtocol.readBattery())
        link.send(IgpsProtocol.readRemainingTime())
        link.send(IgpsProtocol.readSmartConfigs())
    }

    private companion object {
        const val OFF_SETTLE_MS = 3_000L
        const val RECONNECT_MIN_INTERVAL_MS = 10_000L
    }
}
