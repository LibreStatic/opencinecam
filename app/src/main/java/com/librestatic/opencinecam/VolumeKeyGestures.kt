/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.view.KeyEvent

/** Main-thread timers for [VolumeKeyGestures]: MainActivity backs it with a Handler, tests with a fake clock. */
interface VolumeKeyScheduler {
    fun now(): Long
    /** Runs [task] after [delayMs] on the input thread; the returned function cancels it. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** What the gestures act on. A direction is +1 or -1; for a pair it already includes the inversion. */
interface VolumeKeyHandler {
    fun perform(action: OperatorAction)
    /**
     * One step of [pair]. Only the first step of a press [announce]s why it cannot act, so a hold
     * never repeats the message. False when the step could not act.
     */
    fun step(pair: VolumeKeyPair, direction: Int, announce: Boolean): Boolean
    fun zoomContinuous(direction: Int, deltaSeconds: Float)
    fun haptic(event: HapticEvent)
    /** One system volume step, +1 up and -1 down, for a press the gestures had to hold back. */
    fun adjustVolume(direction: Int)
}

/**
 * Turns volume key events into operator gestures: press, hold, double press, both keys, or a −/+
 * pair that steps on a press and keeps stepping while held. A key whose only job is its press acts
 * on key-down with no added latency, and one that is plain system volume is never consumed.
 * System repeats are ignored; the engine keeps its own time. An up whose down was consumed is
 * always consumed, even after the mapping changed.
 */
class VolumeKeyGestures(
    private val scheduler: VolumeKeyScheduler,
    private val longPressMs: Long,
    private val doubleTapMs: Long,
) {
    private class Held(val press: OperatorAction = OperatorAction.NONE, val double: OperatorAction = OperatorAction.NONE,
        val handler: VolumeKeyHandler? = null, settled: Boolean = false) {
        /** True once this press acted in a way its release must not repeat. */
        var done = settled
        var cancel: (() -> Unit)? = null
        fun stop() { cancel?.invoke(); cancel = null }
    }
    private class PendingTap(val key: Int, val double: OperatorAction, val fire: () -> Unit, val cancel: () -> Unit)

    private val held = mutableMapOf<Int, Held>()
    private var pending: PendingTap? = null
    private var chord = false

    /** Drops every held key, pending press and running loop without acting. */
    fun clear() {
        held.values.forEach(Held::stop); held.clear()
        pending?.cancel?.invoke(); pending = null
        chord = false
    }

    /** True when the event is consumed. [prefs] is null when the keys are not the camera's right now. */
    fun dispatch(key: Int, down: Boolean, repeat: Int, cancelled: Boolean, prefs: OperatorPreferences?, handler: VolumeKeyHandler?): Boolean {
        if (!down) return release(key, cancelled)
        if (key in held) return true
        if (repeat != 0 || cancelled || prefs == null || handler == null) return false
        if (chord) { held[key] = Held(settled = true); return true }
        pending?.takeIf { it.key != key }?.let { pending = null; it.cancel(); it.fire() }
        val both = prefs.volumeBoth
        val other = held.entries.firstOrNull { it.key != key }?.value
        if (both != OperatorAction.NONE && other != null && (prefs.volumePair != VolumeKeyPair.NONE || !other.done)) {
            other.stop(); other.done = true
            held[key] = Held(settled = true); chord = true
            handler.perform(both)
            return true
        }
        val pair = prefs.volumePair
        if (pair != VolumeKeyPair.NONE) {
            val direction = keyDirection(key) * (if (prefs.volumePairInverted) -1 else 1)
            val state = Held(settled = true); held[key] = state
            if (handler.step(pair, direction, announce = true))
                state.cancel = scheduler.schedule(longPressMs) { loop(state, pair, direction, handler) }
            return true
        }
        val up = key == KeyEvent.KEYCODE_VOLUME_UP
        val press = if (up) prefs.volumeUp else prefs.volumeDown
        val long = if (up) prefs.volumeUpLong else prefs.volumeDownLong
        val double = if (up) prefs.volumeUpDouble else prefs.volumeDownDouble
        if (long == OperatorAction.NONE && double == OperatorAction.NONE && both == OperatorAction.NONE) {
            if (press == OperatorAction.SYSTEM_VOLUME) return false
            held[key] = Held(settled = true)
            if (press != OperatorAction.NONE) handler.perform(press)
            return true
        }
        pending?.takeIf { it.key == key }?.let { tap ->
            pending = null; tap.cancel()
            held[key] = Held(settled = true)
            handler.perform(tap.double)
            return true
        }
        val state = Held(press, double, handler); held[key] = state
        if (long != OperatorAction.NONE) state.cancel = scheduler.schedule(longPressMs) {
            state.cancel = null; state.done = true
            handler.haptic(HapticEvent.Limit)
            handler.perform(long)
        }
        return true
    }

    private fun release(key: Int, cancelled: Boolean): Boolean {
        val state = held.remove(key) ?: return false
        state.stop()
        if (chord) { if (held.isEmpty()) chord = false; return true }
        if (state.done || cancelled) return true
        val handler = state.handler ?: return true
        val fire = { firePress(key, state.press, handler) }
        if (state.double != OperatorAction.NONE) {
            lateinit var tap: PendingTap
            tap = PendingTap(key, state.double, fire, scheduler.schedule(doubleTapMs) { if (pending === tap) { pending = null; fire() } })
            pending = tap
        } else fire()
        return true
    }

    private fun firePress(key: Int, press: OperatorAction, handler: VolumeKeyHandler) {
        when (press) {
            OperatorAction.NONE -> Unit
            OperatorAction.SYSTEM_VOLUME -> handler.adjustVolume(keyDirection(key))
            else -> handler.perform(press)
        }
    }

    private fun loop(state: Held, pair: VolumeKeyPair, direction: Int, handler: VolumeKeyHandler) {
        if (pair == VolumeKeyPair.ZOOM) {
            var last = scheduler.now()
            fun tick() {
                val now = scheduler.now()
                handler.zoomContinuous(direction, ((now - last).coerceAtLeast(0L) / 1000f).coerceAtMost(MAX_ZOOM_TICK_SECONDS))
                last = now
                state.cancel = scheduler.schedule(ZOOM_TICK_MS, ::tick)
            }
            state.cancel = scheduler.schedule(ZOOM_TICK_MS, ::tick)
        } else {
            fun tick() {
                handler.step(pair, direction, announce = false)
                state.cancel = scheduler.schedule(STEP_REPEAT_MS, ::tick)
            }
            tick()
        }
    }

    companion object {
        /** About one display frame, so a held zoom moves as smoothly as the rocker. */
        const val ZOOM_TICK_MS = 16L
        const val STEP_REPEAT_MS = 150L
        /** A stalled frame never turns into a jump. */
        const val MAX_ZOOM_TICK_SECONDS = 0.1f
        fun keyDirection(key: Int): Int = if (key == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
    }
}
