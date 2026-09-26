/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The service-level take admission handed to the engine by one VIDEO/LOG/TIME_LAPSE start.
 *
 * Exactly one terminal owner releases the dispatched reservation: the engine's stop callback,
 * or a start failure observed before RECORDING (and only once native preparation retired).
 * Claims are keyed on the exact dispatch, so a late or repeated failure can never release a
 * newer take's admission and a stop after a released failed start never releases twice.
 */
internal class DispatchedTakeAdmission<R : Any> {
    class Dispatch<R : Any> internal constructor(val reservation: R) {
        internal val state = AtomicInteger(DISPATCHED)
    }

    private val current = AtomicReference<Dispatch<R>?>(null)

    /** Records the exact reservation handed to the engine. A stale unclaimed dispatch is superseded. */
    fun dispatch(reservation: R): Dispatch<R> = Dispatch(reservation).also { next ->
        current.getAndSet(next)?.state?.set(RELEASED)
    }

    /** The engine reported RECORDING: from now on only [claimStopped] releases this dispatch. */
    fun markStarted() {
        current.get()?.state?.compareAndSet(DISPATCHED, STARTED)
    }

    /** The current dispatch when it never reached RECORDING and is not yet claimed. */
    fun pendingStart(): Dispatch<R>? = current.get()?.takeIf { it.state.get() == DISPATCHED }

    /** Claims a failed start exactly once; null when it started, was claimed, or was superseded. */
    fun claimFailedStart(dispatch: Dispatch<R>): R? {
        if (!dispatch.state.compareAndSet(DISPATCHED, RELEASED)) return null
        current.compareAndSet(dispatch, null)
        return dispatch.reservation
    }

    /** Claims the dispatch terminated by the engine's stop callback; null if already claimed. */
    fun claimStopped(): R? {
        val dispatch = current.getAndSet(null) ?: return null
        return if (dispatch.state.getAndSet(RELEASED) != RELEASED) dispatch.reservation else null
    }

    private companion object {
        const val DISPATCHED = 0
        const val STARTED = 1
        const val RELEASED = 2
    }
}
