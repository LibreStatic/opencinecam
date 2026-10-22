/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/**
 * Preparation closes/writes an owned pending output without publishing it. A split preparation
 * may be aborted reentrantly, but cleanup waits until its callback returns. Legacy finish(true)
 * and publication claim the first completion before callbacks; nested completion cannot replace
 * their outcome. No callback observes a published result until publication actually succeeds.
 */
internal class PendingOutputTransaction<T : Any>(
    private val value: T,
    private val close: () -> Unit,
    private val delete: () -> Unit,
) {
    private enum class State { OPEN, PREPARING, PREPARED, PUBLISHING, PUBLISHED, ABORTED }
    private var state = State.OPEN
    private var descriptorClosed = false
    private var deleteAttempted = false
    private var abortRequested = false
    private var legacyFinishing = false

    @Synchronized
    fun prepare(prepare: () -> Unit = {}): T? {
        when (state) {
            State.PREPARED, State.PUBLISHED -> return value
            State.OPEN -> Unit
            else -> return null
        }
        state = State.PREPARING
        try {
            closeOnce()
            if (!abortRequested) prepare()
            if (abortRequested) return abort()
            state = State.PREPARED
            return value
        } catch (failure: Throwable) {
            return abort(failure)
        }
    }

    @Synchronized
    fun publish(publish: () -> Unit = {}): T? {
        if (state == State.PUBLISHED) return value
        if (state != State.PREPARED) return null
        state = State.PUBLISHING
        try {
            publish()
            state = State.PUBLISHED
            return value
        } catch (failure: Throwable) {
            return abort(failure)
        }
    }

    @Synchronized
    fun finish(success: Boolean, prepare: () -> Unit = {}, publish: () -> Unit = {}): T? {
        if (state == State.PUBLISHED) return value
        if (legacyFinishing || state == State.PUBLISHING || state == State.ABORTED) return null
        if (state == State.PREPARING) {
            if (!success) abortRequested = true
            return null
        }
        if (!success) return abort()
        legacyFinishing = true
        return try {
            if (this.prepare(prepare) == null) null else this.publish(publish)
        } finally {
            legacyFinishing = false
        }
    }

    /**
     * After an abort whose delete callback deliberately kept the output (ADR-0011: it holds bytes),
     * allow one salvage attempt: back to PREPARED when it had been prepared, else to OPEN. The
     * descriptor stays closed. No effect unless the transaction was aborted.
     */
    @Synchronized
    fun resumeAfterRetainedAbort(wasPrepared: Boolean) {
        if (state != State.ABORTED) return
        state = if (wasPrepared) State.PREPARED else State.OPEN
        deleteAttempted = false
        abortRequested = false
    }

    private fun closeOnce() {
        if (descriptorClosed) return
        descriptorClosed = true
        close()
    }

    private fun abort(initialFailure: Throwable? = null): T? {
        // Mark terminal before cleanup callbacks, which may themselves reenter the transaction.
        state = State.ABORTED
        var failure = initialFailure
        fun retain(problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        try { closeOnce() } catch (problem: Throwable) { retain(problem) }
        if (!deleteAttempted) {
            deleteAttempted = true
            try { delete() } catch (problem: Throwable) { retain(problem) }
        }
        failure?.let { throw it }
        return null
    }
}
