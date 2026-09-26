package com.tiagodias.igpsportkaroo.light

import com.tiagodias.igpsportkaroo.ble.LightLink
import com.tiagodias.igpsportkaroo.ble.LinkEvent
import com.tiagodias.igpsportkaroo.protocol.FrameAssembler
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber

/** Turns link events into [LightState] and light commands into frames, for one paired light. */
class LightSession(
    private val link: LightLink,
    private val address: String,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LightState())
    val state: StateFlow<LightState> = _state.asStateFlow()

    private val assembler = FrameAssembler()
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
                    _state.update { it.copy(connected = true) }
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
                    _state.update { it.copy(connected = false) }
                }
                is LinkEvent.Fragment -> assembler.push(event.bytes).forEach { frame ->
                    IgpsProtocol.parseFrame(frame)?.let { update -> _state.update { applyReport(it, update) } }
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
            _state.update { it.copy(poweredOff = true) }
            return true
        }
        if (_state.value.declaredModes[mode] == false) {
            if (!link.send(IgpsProtocol.setModeEnabled(mode, true))) return false
            link.send(IgpsProtocol.readSupportedModes())
        }
        if (!link.send(IgpsProtocol.setMode(mode))) return false
        offCommandAt = null
        // Optimistic: the field shows the new mode right away; the read-back below confirms or corrects it.
        _state.update { it.copy(mode = mode, poweredOff = false) }
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

    /** A mode report ends "off" unless it is the echo the light sends right after our OFF command. */
    private fun applyReport(state: LightState, update: LightUpdate): LightState {
        val applied = state.apply(update)
        val settling = synchronized(commandLock) { offCommandAt?.let { now() - it < OFF_SETTLE_MS } ?: false }
        return if (state.poweredOff && update.mode != null && !settling) applied.copy(poweredOff = false) else applied
    }

    private fun refreshAll() {
        link.send(IgpsProtocol.readSupportedModes())
        link.send(IgpsProtocol.readCurrentMode())
        link.send(IgpsProtocol.readBattery())
        link.send(IgpsProtocol.readRemainingTime())
    }

    private companion object {
        const val OFF_SETTLE_MS = 3_000L
        const val RECONNECT_MIN_INTERVAL_MS = 10_000L
    }
}
